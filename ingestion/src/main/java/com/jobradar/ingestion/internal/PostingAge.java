package com.jobradar.ingestion.internal;

import java.time.Duration;
import java.time.Instant;

/** Freshness rules of docs/RESEARCH.md, "Freshness first", as far as ingestion needs them. */
public final class PostingAge {

    /** Postings younger than this go to the hot queue. */
    public static final Duration HOT_WINDOW = Duration.ofHours(2);

    private PostingAge() {}

    /**
     * Age = now minus the earlier of {@code postedAt} (when the source gives it) and
     * {@code firstSeenAt}. Never negative: a source clock ahead of ours yields zero.
     */
    public static Duration of(Instant postedAt, Instant firstSeenAt, Instant now) {
        Instant reference = postedAt != null && postedAt.isBefore(firstSeenAt) ? postedAt : firstSeenAt;
        Duration age = Duration.between(reference, now);
        return age.isNegative() ? Duration.ZERO : age;
    }

    public static boolean isHot(Instant postedAt, Instant firstSeenAt, Instant now) {
        return of(postedAt, firstSeenAt, now).compareTo(HOT_WINDOW) < 0;
    }
}
