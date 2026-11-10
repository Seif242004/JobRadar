package com.jobradar.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jobradar.matching.Band;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Band and priority rules from docs/PLAN.md, "Freshness first". */
class BandsTests {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void hotIsUnderTwoHoursWithFitOfAtLeast80() {
        assertThat(Bands.classify(Duration.ofMinutes(119), 80.0, null)).isEqualTo(Band.HOT);
        assertThat(Bands.classify(Duration.ofMinutes(119), 79.9, null)).isEqualTo(Band.WARM);
        assertThat(Bands.classify(Duration.ofHours(2), 95.0, null)).isEqualTo(Band.WARM);
    }

    @Test
    void withoutAFitScoreAgeAloneDecidesTheHotBand() {
        // SPEC.md D8: milestone M1 has no fit score yet.
        assertThat(Bands.classify(Duration.ofMinutes(10), null, null)).isEqualTo(Band.HOT);
    }

    @Test
    void warmIsTwoTo24HoursAndBacklogIsOlder() {
        assertThat(Bands.classify(Duration.ofHours(23), 90.0, null)).isEqualTo(Band.WARM);
        assertThat(Bands.classify(Duration.ofHours(24), 90.0, null)).isEqualTo(Band.BACKLOG);
    }

    @Test
    void aClosingDatePutsThePostingInTheDeadlineBandWhateverItsAge() {
        Instant deadline = NOW.plus(Duration.ofDays(10));
        assertThat(Bands.classify(Duration.ofMinutes(5), 90.0, deadline)).isEqualTo(Band.DEADLINE);
        assertThat(Bands.classify(Duration.ofDays(5), 90.0, deadline)).isEqualTo(Band.DEADLINE);
    }

    @Test
    void priorityHalvesEverySixHours() {
        assertThat(Bands.priority(80, Duration.ZERO)).isCloseTo(80.0, within(1e-9));
        assertThat(Bands.priority(80, Duration.ofHours(6))).isCloseTo(40.0, within(1e-9));
        assertThat(Bands.priority(80, Duration.ofHours(12))).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void aStrongMatchFromThisMorningOutranksASlightlyBetterOneFromYesterday() {
        assertThat(Bands.priority(85, Duration.ofHours(4))).isGreaterThan(Bands.priority(90, Duration.ofHours(26)));
    }

    @Test
    void ageCountsFromTheEarlierOfPostedAtAndFirstSeenAt() {
        Instant firstSeen = NOW.minus(Duration.ofHours(1));
        assertThat(Bands.age(NOW.minus(Duration.ofHours(5)), firstSeen, NOW)).isEqualTo(Duration.ofHours(5));
        assertThat(Bands.age(null, firstSeen, NOW)).isEqualTo(Duration.ofHours(1));
    }
}
