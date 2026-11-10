package com.jobradar;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
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

    /** Signs CV download links locally; no request leaves the process. */
    @Bean
    S3Presigner s3Presigner(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // LocalStack serves buckets under the path, not as a sub-domain.
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    /** Reads generated CVs when the user approves an application email. */
    @Bean
    S3Client s3Client(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    /** Reads the users' Gmail refresh tokens. */
    @Bean
    SecretsManagerClient secretsManagerClient(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        var builder = SecretsManagerClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
