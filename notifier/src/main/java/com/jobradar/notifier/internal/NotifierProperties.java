package com.jobradar.notifier.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jobradar.notifier.*}.
 *
 * @param cvBucket  bucket that holds the generated CVs
 * @param cvLinkTtl how long a CV link in an alert stays valid
 */
@ConfigurationProperties("jobradar.notifier")
public record NotifierProperties(String cvBucket, @DefaultValue("12h") Duration cvLinkTtl, Queues queues) {

    public record Queues(String cvReadyUrl) {}
}
