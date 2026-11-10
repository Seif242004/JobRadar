package com.jobradar.ingestion.internal.schedule;

import com.jobradar.ingestion.IngestionService;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.internal.PostingRepository;
import com.jobradar.ingestion.internal.resolver.CareersResolver;
import com.jobradar.ingestion.internal.resolver.WatchlistSeeder;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the in-process schedule on with {@code jobradar.ingestion.scheduling.enabled=true}.
 * Off by default, so tests and any process that merely has the module on its classpath never
 * call a source on their own.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jobradar.ingestion.scheduling.enabled", havingValue = "true")
class SchedulingConfiguration {

    @Bean
    ConnectorScheduler connectorScheduler(
            List<SourceConnector> connectors,
            IngestionService ingestion,
            SourceRunRepository runs,
            PostingRepository postings,
            WatchlistSeeder seeder,
            CareersResolver resolver,
            Clock clock) {
        return new ConnectorScheduler(connectors, ingestion, runs, postings, seeder, resolver, clock);
    }
}
