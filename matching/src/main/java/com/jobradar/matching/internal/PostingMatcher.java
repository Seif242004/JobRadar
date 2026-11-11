package com.jobradar.matching.internal;

import com.jobradar.matching.Band;
import com.jobradar.matching.MatchScored;
import com.jobradar.profile.ProfileService;
import com.jobradar.profile.UserPreferences;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Flow 2 for one posting: run the hard filters for every user, store the match, announce it.
 *
 * <p>Only eligible postings are scored. When no fit score can be computed (embeddings unavailable,
 * or a profile without bullets) {@code fit_score} and {@code priority} stay null and the band
 * depends on age alone (SPEC.md D8).
 */
@Component
public class PostingMatcher {

    private static final Logger log = LoggerFactory.getLogger(PostingMatcher.class);

    private final MatchRepository matches;
    private final FitScorer fitScorer;
    private final ProfileService profiles;
    private final ApplicationEventPublisher events;
    private final TailorRequestPublisher tailorRequests;
    private final FitProperties fitProperties;
    private final Clock clock;

    PostingMatcher(
            MatchRepository matches,
            FitScorer fitScorer,
            ProfileService profiles,
            ApplicationEventPublisher events,
            TailorRequestPublisher tailorRequests,
            FitProperties fitProperties,
            Clock clock) {
        this.matches = matches;
        this.fitScorer = fitScorer;
        this.profiles = profiles;
        this.events = events;
        this.tailorRequests = tailorRequests;
        this.fitProperties = fitProperties;
        this.clock = clock;
    }

    /**
     * @return false when the posting does not exist, which happens when ingestion published the
     *         message but its transaction rolled back; the message is then simply dropped
     */
    public boolean match(long postingId) {
        Optional<PostingView> found = matches.findPosting(postingId);
        if (found.isEmpty()) {
            return false;
        }
        PostingView posting = found.get();
        Instant now = clock.instant();
        Duration age = Bands.age(posting.postedAt(), posting.firstSeenAt(), now);

        for (UserPreferences user : profiles.findAllPreferences()) {
            matchFor(posting, user, now, age);
        }
        return true;
    }

    private void matchFor(PostingView posting, UserPreferences user, Instant now, Duration age) {
        long postingId = posting.id();
        Eligibility eligibility = EligibilityFilter.check(posting, user, now);
        // The hard filters run first: an ineligible posting costs no embedding or LLM call.
        Optional<Fit> fit = eligibility.eligible() ? fitScorer.score(posting, user.userId()) : Optional.empty();
        Double fitScore = fit.map(Fit::score).orElse(null);
        Double priority = fit.map(f -> Bands.priority(f.score(), age)).orElse(null);
        Band band = Bands.classify(age, fitScore, posting.deadline());
        String reasons = eligibility.reason() + fit.map(f -> "; " + f.reason()).orElse("");
        matches.upsert(user.userId(), postingId, eligibility.eligible(), fitScore, priority, band, reasons);
        requestCv(user.userId(), postingId, eligibility.eligible(), fitScore, band);
        events.publishEvent(new MatchScored(user.userId(), postingId, eligibility.eligible(), band, fitScore));
    }

    /**
     * Re-runs the hard filters of one user over every stored posting after the user changed
     * preferences. A posting whose verdict did not change is left alone, so only newly eligible
     * postings cost an embedding call. Safe to repeat.
     *
     * @return how many postings changed verdict
     */
    public int rematch(long userId) {
        Optional<UserPreferences> user = profiles.findPreferences(userId);
        if (user.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        java.util.Map<Long, Boolean> previous = matches.eligibilityByPosting(userId);
        int changed = 0;
        for (long postingId : matches.allPostingIds()) {
            Optional<PostingView> posting = matches.findPosting(postingId);
            if (posting.isEmpty()) {
                continue;
            }
            boolean eligible = EligibilityFilter.check(posting.get(), user.get(), now).eligible();
            if (previous.containsKey(postingId) && previous.get(postingId) == eligible) {
                continue;
            }
            Duration age = Bands.age(posting.get().postedAt(), posting.get().firstSeenAt(), now);
            matchFor(posting.get(), user.get(), now, age);
            changed++;
        }
        log.info("Rematched user {}: {} postings changed verdict", userId, changed);
        return changed;
    }

    /**
     * Hot and Warm matches with a fit score of at least {@code tailorMinFit} get a tailored CV
     * (SPEC.md, "tailoring consumes Hot/Warm"): Hot ones on the hot tailoring queue, Warm ones on
     * the backlog queue. Without a fit score
     * nothing is tailored, so an unavailable LLM or embedding endpoint never queues work.
     *
     * <p>A failed publish is logged and does not fail the match: redelivering the posting would
     * repeat the fit scoring and its LLM call.
     */
    private void requestCv(long userId, long postingId, boolean eligible, Double fitScore, Band band) {
        if (!eligible || fitScore == null || (band != Band.HOT && band != Band.WARM) || fitScore < fitProperties.tailorMinFit()) {
            return;
        }
        try {
            tailorRequests.publish(userId, postingId, band == Band.HOT);
        } catch (RuntimeException e) {
            log.warn("Could not request a CV for user {} posting {}", userId, postingId, e);
        }
    }
}
