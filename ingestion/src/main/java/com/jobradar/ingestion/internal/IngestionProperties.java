package com.jobradar.ingestion.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code jobradar.ingestion.*}: the URLs of the two work queues. */
@ConfigurationProperties("jobradar.ingestion")
public record IngestionProperties(Queues queues) {

    public record Queues(String hotUrl, String backlogUrl) {}
}
