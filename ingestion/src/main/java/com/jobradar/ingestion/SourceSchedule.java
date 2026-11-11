package com.jobradar.ingestion;

import java.time.Duration;

/**
 * How often a source may be called. Both limits hold at once.
 *
 * @param minInterval    shortest time between the starts of two runs
 * @param maxRunsPerDay  most runs in any rolling 24 hours; {@code 0} means no daily limit
 */
public record SourceSchedule(Duration minInterval, int maxRunsPerDay) {

    /** SPEC.md D6: ATS sources are polled every 15 minutes. */
    public static final SourceSchedule ATS = new SourceSchedule(Duration.ofMinutes(15), 0);
}
