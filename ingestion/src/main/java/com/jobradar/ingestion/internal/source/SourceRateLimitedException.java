package com.jobradar.ingestion.internal.source;

import java.time.Duration;

/**
 * The source answered HTTP 429. The scheduler stops calling it until {@link #retryAfter()} has
 * passed; a rate limit is never retried early or worked around.
 */
public class SourceRateLimitedException extends SourceFetchException {

    /** Used when the source sends no {@code Retry-After}. */
    public static final Duration DEFAULT_BACK_OFF = Duration.ofHours(1);

    private final Duration retryAfter;

    public SourceRateLimitedException(String source, Duration retryAfter) {
        super(source + " answered 429 (rate limited)");
        this.retryAfter = retryAfter == null ? DEFAULT_BACK_OFF : retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
