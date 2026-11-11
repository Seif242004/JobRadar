package com.jobradar.ingestion.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.SourceSchedule;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunPolicyTests {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final SourceSchedule REMOTIVE = new SourceSchedule(Duration.ofHours(6), 4);

    @Test
    void aSourceThatNeverRanIsDue() {
        assertThat(RunPolicy.isDue(SourceSchedule.ATS, null, 0, null, NOW)).isTrue();
    }

    @Test
    void atsSourcesRunEvery15Minutes() {
        assertThat(RunPolicy.isDue(SourceSchedule.ATS, NOW.minus(Duration.ofMinutes(14)), 50, null, NOW)).isFalse();
        assertThat(RunPolicy.isDue(SourceSchedule.ATS, NOW.minus(Duration.ofMinutes(15)), 50, null, NOW)).isTrue();
    }

    @Test
    void remotiveWaitsSixHoursBetweenRuns() {
        assertThat(RunPolicy.isDue(REMOTIVE, NOW.minus(Duration.ofHours(5)), 1, null, NOW)).isFalse();
        assertThat(RunPolicy.isDue(REMOTIVE, NOW.minus(Duration.ofHours(6)), 1, null, NOW)).isTrue();
    }

    @Test
    void remotiveNeverGetsAFifthCallInADayEvenWhenTheIntervalHasPassed() {
        assertThat(RunPolicy.isDue(REMOTIVE, NOW.minus(Duration.ofHours(7)), 4, null, NOW)).isFalse();
        assertThat(RunPolicy.isDue(REMOTIVE, NOW.minus(Duration.ofHours(7)), 3, null, NOW)).isTrue();
    }

    @Test
    void aBackOffTheSourceAskedForIsWaitedOut() {
        Instant blockedUntil = NOW.plus(Duration.ofHours(2));

        assertThat(RunPolicy.isDue(SourceSchedule.ATS, null, 0, blockedUntil, NOW)).isFalse();
        assertThat(RunPolicy.isDue(SourceSchedule.ATS, null, 0, blockedUntil, blockedUntil)).isTrue();
    }
}
