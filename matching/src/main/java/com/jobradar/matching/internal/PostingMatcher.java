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
 * <p>Milestone M1 has no fit score yet, so {@code fit_score} and {@code priority} stay null and
 * the band depends on age alone (SPEC.md D8).
 */
@Component
public class PostingMatcher {

    private final MatchRepository matches;
    private final ProfileService profiles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PostingMatcher(MatchRepository matches, ProfileService profiles, ApplicationEventPublisher events, Clock clock) {
        this.matches = matches;
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
            Band band = Bands.classify(age, null, posting.deadline());
            matches.upsert(user.userId(), postingId, eligibility.eligible(), null, null, band, eligibility.reason());
            events.publishEvent(new MatchScored(user.userId(), postingId, eligibility.eligible(), band));
        }
        return true;
    }
}
