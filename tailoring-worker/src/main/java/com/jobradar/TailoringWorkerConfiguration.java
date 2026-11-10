package com.jobradar;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Infrastructure beans of the tailoring-worker deployable. */
@Configuration
@EnableScheduling
class TailoringWorkerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Credentials come from the default AWS chain; the endpoint override is for LocalStack only. */
    @Bean
    SqsClient sqsClient(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    S3Client s3Client(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // LocalStack serves buckets under the path, not as a sub-domain.
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }
}
