package com.jobradar.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Age and hot-queue rules from docs/RESEARCH.md, "Freshness first". */
class PostingAgeTests {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void ageCountsFromTheEarlierOfPostedAtAndFirstSeenAt() {
        Instant posted = NOW.minus(Duration.ofHours(5));
        Instant firstSeen = NOW.minus(Duration.ofHours(1));

        assertThat(PostingAge.of(posted, firstSeen, NOW)).isEqualTo(Duration.ofHours(5));
        // A posted_at later than our first sighting cannot make the posting younger.
        assertThat(PostingAge.of(NOW, firstSeen, NOW)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void withoutPostedAtAgeCountsFromFirstSeenAt() {
        assertThat(PostingAge.of(null, NOW, NOW)).isEqualTo(Duration.ZERO);
        assertThat(PostingAge.of(null, NOW.minus(Duration.ofHours(3)), NOW)).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void hotMeansStrictlyUnderTwoHours() {
        assertThat(PostingAge.isHot(NOW.minus(Duration.ofMinutes(119)), NOW, NOW)).isTrue();
        assertThat(PostingAge.isHot(NOW.minus(Duration.ofHours(2)), NOW, NOW)).isFalse();
        // No posted_at: fresh on the first sighting.
        assertThat(PostingAge.isHot(null, NOW, NOW)).isTrue();
    }

    @Test
    void aSourceClockAheadOfOursNeverYieldsANegativeAge() {
        assertThat(PostingAge.of(null, NOW.plusSeconds(30), NOW)).isEqualTo(Duration.ZERO);
    }
}
