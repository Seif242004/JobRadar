package com.jobradar.ingestion.internal;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfiguration {

    /** Injected everywhere time is read, so tests can fix "now". */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Credentials come from the default AWS chain (the task role on Fargate, env vars locally).
     * The endpoint override is set only for LocalStack.
     */
    @Bean
    @ConditionalOnMissingBean
    SqsClient sqsClient(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
