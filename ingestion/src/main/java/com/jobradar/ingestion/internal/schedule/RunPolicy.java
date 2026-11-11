package com.jobradar.ingestion.internal.schedule;

import com.jobradar.ingestion.SourceSchedule;
import java.time.Instant;

/** Decides whether a source may be called now. Pure, so the limits are unit-tested directly. */
final class RunPolicy {

    private RunPolicy() {}

    /**
     * @param lastRunAt     start of the source's latest run, or null when it never ran
     * @param runsLast24h   runs started in the last 24 hours, failed ones included
     * @param blockedUntil  end of a back-off the source asked for (HTTP 429), or null
     */
    static boolean isDue(SourceSchedule schedule, Instant lastRunAt, int runsLast24h, Instant blockedUntil, Instant now) {
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            return false;
        }
        if (schedule.maxRunsPerDay() > 0 && runsLast24h >= schedule.maxRunsPerDay()) {
            return false;
        }
        return lastRunAt == null || !now.isBefore(lastRunAt.plus(schedule.minInterval()));
    }
}
