package com.jobradar.matching.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jobradar.matching.*}: the queues the api consumes. */
@ConfigurationProperties("jobradar.matching")
public record MatchingProperties(Queues queues) {

    /**
     * @param hotUrl            consumed: postings under 2 hours old
     * @param backlogUrl        consumed: all other postings
     * @param tailorHotUrl      produced: CV requests for Hot matches
     * @param tailorBacklogUrl  produced: CV requests for Warm matches
     */
    public record Queues(String hotUrl, String backlogUrl, String tailorHotUrl, String tailorBacklogUrl) {}
}
