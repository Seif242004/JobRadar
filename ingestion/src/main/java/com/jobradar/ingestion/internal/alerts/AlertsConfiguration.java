package com.jobradar.ingestion.internal.alerts;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;

@Configuration
@EnableConfigurationProperties(GmailAlertProperties.class)
class AlertsConfiguration {

    /** Same region and optional LocalStack endpoint as the SQS client; credentials come from the default AWS chain. */
    @Bean
    @ConditionalOnMissingBean
    SecretsManagerClient secretsManagerClient(
            @Value("${jobradar.aws.region}") String region, @Value("${jobradar.aws.endpoint:}") String endpoint) {
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
