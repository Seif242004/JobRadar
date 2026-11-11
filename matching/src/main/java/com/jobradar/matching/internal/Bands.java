package com.jobradar.matching.internal;

import com.jobradar.matching.Band;
import java.time.Duration;
import java.time.Instant;

/** Band and priority rules of docs/PLAN.md, "Freshness first". */
public final class Bands {

    static final Duration HOT_WINDOW = Duration.ofHours(2);
    static final Duration WARM_WINDOW = Duration.ofDays(7);
    static final double HOT_MIN_FIT = 80.0;
    static final double HALF_LIFE_HOURS = 6.0;

    private Bands() {}

    /** Age = now minus the earlier of {@code postedAt} and {@code firstSeenAt}; never negative. */
    public static Duration age(Instant postedAt, Instant firstSeenAt, Instant now) {
        Instant reference = postedAt != null && postedAt.isBefore(firstSeenAt) ? postedAt : firstSeenAt;
        Duration age = Duration.between(reference, now);
        return age.isNegative() ? Duration.ZERO : age;
    }

    /**
     * @param fitScore 0 to 100, or null while no fit score exists (milestone M1, SPEC.md D8); a
     *                 null fit does not block the hot band
     * @param deadline closing date, or null
     */
    public static Band classify(Duration age, Double fitScore, Instant deadline) {
        if (deadline != null) {
            return Band.DEADLINE;
        }
        if (age.compareTo(HOT_WINDOW) < 0 && (fitScore == null || fitScore >= HOT_MIN_FIT)) {
            return Band.HOT;
        }
        // A fresh posting with a fit below 80 is not worth an instant push; it joins the batch.
        return age.compareTo(WARM_WINDOW) < 0 ? Band.WARM : Band.BACKLOG;
    }

    /** Priority = fit x freshness, where freshness halves every 6 hours. */
    public static double priority(double fitScore, Duration age) {
        double ageHours = age.toMillis() / 3_600_000.0;
        return fitScore * Math.pow(0.5, ageHours / HALF_LIFE_HOURS);
    }
}
