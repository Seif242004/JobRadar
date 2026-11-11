package com.jobradar.ingestion.internal.source;

import java.time.Duration;

/**
 * An HTTP answer.
 *
 * @param status     HTTP status code
 * @param body       response body as text; never null
 * @param retryAfter the {@code Retry-After} header in its seconds form, or null when absent
 */
public record FetchResponse(int status, String body, Duration retryAfter) {

    public static FetchResponse ok(String body) {
        return new FetchResponse(200, body, null);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
