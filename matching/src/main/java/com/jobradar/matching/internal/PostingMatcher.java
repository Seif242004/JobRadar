package com.jobradar.matching.internal;

import com.jobradar.matching.Band;
import com.jobradar.matching.MatchScored;
import com.jobradar.profile.ProfileService;
import com.jobradar.profile.UserPreferences;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

    private final MatchRepository matches;
    private final FitScorer fitScorer;
    private final ProfileService profiles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PostingMatcher(
            MatchRepository matches,
            FitScorer fitScorer,
            ProfileService profiles,
            ApplicationEventPublisher events,
            Clock clock) {
        this.matches = matches;
        this.fitScorer = fitScorer;
        this.profiles = profiles;
        this.events = events;
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
            Eligibility eligibility = EligibilityFilter.check(posting, user, now);
            // The hard filters run first: an ineligible posting costs no embedding or LLM call.
            Optional<Fit> fit = eligibility.eligible() ? fitScorer.score(posting, user.userId()) : Optional.empty();
            Double fitScore = fit.map(Fit::score).orElse(null);
            Double priority = fit.map(f -> Bands.priority(f.score(), age)).orElse(null);
            Band band = Bands.classify(age, fitScore, posting.deadline());
            String reasons = eligibility.reason() + fit.map(f -> "; " + f.reason()).orElse("");
            matches.upsert(user.userId(), postingId, eligibility.eligible(), fitScore, priority, band, reasons);
            events.publishEvent(new MatchScored(user.userId(), postingId, eligibility.eligible(), band));
        }
        return true;
    }
}
