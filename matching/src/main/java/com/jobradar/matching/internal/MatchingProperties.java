package com.jobradar.matching.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jobradar.matching.*}: the queues the api consumes. */
@ConfigurationProperties("jobradar.matching")
public record MatchingProperties(Queues queues) {

    public record Queues(String hotUrl, String backlogUrl) {}
}
