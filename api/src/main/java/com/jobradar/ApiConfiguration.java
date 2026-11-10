package com.jobradar;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Infrastructure beans shared by the modules inside the api deployable. */
@Configuration
@EnableScheduling
class ApiConfiguration {

    /** Injected everywhere time is read, so tests can fix "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Credentials come from the default AWS chain (the task role on Fargate, env vars locally).
     * The endpoint override is set only for LocalStack.
     */
    @Bean
    SqsClient sqsClient(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
