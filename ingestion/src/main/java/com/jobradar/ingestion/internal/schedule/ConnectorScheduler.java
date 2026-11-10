package com.jobradar.ingestion.internal.schedule;

import com.jobradar.ingestion.IngestionResult;
import com.jobradar.ingestion.IngestionService;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.internal.PostingRepository;
import com.jobradar.ingestion.internal.resolver.CareersResolver;
import com.jobradar.ingestion.internal.resolver.WatchlistSeeder;
import com.jobradar.ingestion.internal.source.SourceRateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs every {@link SourceConnector} on its own schedule and keeps the watchlist resolved.
 *
 * <p>A one-minute tick asks {@link RunPolicy} whether each source is due. Run history comes from
 * {@code source_run}, and failed runs are recorded there too, so an erroring source is not
 * called again before its interval and a failed Remotive call still counts towards its 4 a day.
 */
public class ConnectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorScheduler.class);

    private final List<SourceConnector> connectors;
    private final IngestionService ingestion;
    private final SourceRunRepository runs;
    private final PostingRepository postings;
    private final WatchlistSeeder seeder;
    private final CareersResolver resolver;
    private final Clock clock;

    /** Back-offs the sources asked for with HTTP 429. In memory: after a restart one call re-learns it. */
    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    ConnectorScheduler(
            List<SourceConnector> connectors,
            IngestionService ingestion,
            SourceRunRepository runs,
            PostingRepository postings,
            WatchlistSeeder seeder,
            CareersResolver resolver,
            Clock clock) {
        this.connectors = connectors;
        this.ingestion = ingestion;
        this.runs = runs;
        this.postings = postings;
        this.seeder = seeder;
        this.resolver = resolver;
        this.clock = clock;
    }

    /** Seeds the watchlist and resolves what is due: at startup, then daily. */
    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT24H")
    public void resolveWatchlist() {
        try {
            int seeded = seeder.seed();
            int resolved = resolver.resolvePending();
            log.info("Watchlist: {} companies seeded, {} resolved in this pass", seeded, resolved);
        } catch (RuntimeException e) {
            log.warn("Watchlist resolution failed", e);
        }
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
    public void tick() {
        for (SourceConnector connector : connectors) {
            try {
                runIfDue(connector);
            } catch (RuntimeException e) {
                // For example the database being down: nothing was recorded, the next tick retries.
                log.warn("Scheduling {} failed", connector.source(), e);
            }
        }
    }

    /** Runs the connector when its schedule allows. Returns whether it was called. */
    boolean runIfDue(SourceConnector connector) {
        String source = connector.source();
        Instant now = clock.instant();
        boolean due = RunPolicy.isDue(
                connector.schedule(),
                runs.lastRunAt(source),
                runs.runsSince(source, now.minus(Duration.ofDays(1))),
                blockedUntil.get(source),
                now);
        if (!due) {
            return false;
        }
        try {
            IngestionResult result = ingestion.run(connector);
            log.info("{}: {} found, {} new, {} hot, {} errors",
                    source, result.itemsFound(), result.newItems(), result.hot(), result.errors());
        } catch (SourceRateLimitedException e) {
            blockedUntil.put(source, now.plus(e.retryAfter()));
            log.warn("{} is rate limited; not calling it again for {}", source, e.retryAfter());
            postings.recordRun(source, now, 0, 0, List.of("rate limited, retry after " + e.retryAfter()));
        } catch (RuntimeException e) {
            log.warn("{} run failed", source, e);
            postings.recordRun(source, now, 0, 0, List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return true;
    }
}
