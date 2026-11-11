package com.jobradar.ingestion.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.IngestionResult;
import com.jobradar.ingestion.IngestionService;
import com.jobradar.ingestion.PostingIngested;
import com.jobradar.ingestion.PostingPublisher;
import com.jobradar.ingestion.PostingQueue;
import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.internal.PostingRepository;
import com.jobradar.ingestion.internal.resolver.CareersResolver;
import com.jobradar.ingestion.internal.resolver.WatchlistSeeder;
import com.jobradar.ingestion.internal.source.FetchResponse;
import com.jobradar.ingestion.internal.source.HttpFetcher;
import com.jobradar.ingestion.internal.source.SourceFetchException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The connectors, the careers resolver and the schedule against real Postgres, with the recorded
 * fixtures of the ingestion module in place of the network and an in-memory queue in place of SQS
 * (the SQS path itself is covered by IngestionPipelineTests).
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceIngestionTests {

    private static final String LEVER_BOSTA = "https://api.lever.co/v0/postings/Bosta?mode=json";
    private static final String WORKABLE_EGYPT = "https://jobs.workable.com/api/v1/jobs?location=Egypt";
    private static final String REMOTIVE_FEED = "https://remotive.com/api/remote-jobs";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Answers known URLs with canned responses; any other URL fails like an unreachable host. */
    static final class FakeHttp implements HttpFetcher {
        final Map<String, FetchResponse> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override
        public FetchResponse get(URI uri) {
            requested.add(uri.toString());
            FetchResponse response = responses.get(uri.toString());
            if (response == null) {
                throw new SourceFetchException("No fixture for " + uri);
            }
            return response;
        }
    }

    /** Collects what would have gone to SQS. */
    static final class RecordingPublisher implements PostingPublisher {
        final List<PostingQueue> queues = new ArrayList<>();

        @Override
        public void publish(PostingIngested event, PostingQueue queue) {
            queues.add(queue);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        @Primary
        FakeHttp fakeHttp() {
            return new FakeHttp();
        }

        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    @Autowired List<SourceConnector> connectors;
    @Autowired IngestionService ingestion;
    @Autowired SourceRunRepository runs;
    @Autowired PostingRepository postings;
    @Autowired WatchlistSeeder seeder;
    @Autowired CareersResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired FakeHttp http;
    @Autowired RecordingPublisher publisher;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE posting, company, source_run CASCADE");
        clock.now = Instant.parse("2026-11-10T12:00:00Z");
        http.responses.clear();
        http.requested.clear();
        publisher.queues.clear();
    }

    private static FetchResponse fixture(String path) {
        try {
            // Recorded once, in the ingestion module; see its fixtures/README.md.
            return FetchResponse.ok(Files.readString(Path.of("../ingestion/src/test/resources/fixtures", path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SourceConnector connector(String source) {
        return connectors.stream().filter(c -> c.source().equals(source)).findFirst().orElseThrow();
    }

    private ConnectorScheduler scheduler() {
        return new ConnectorScheduler(connectors, ingestion, runs, postings, seeder, resolver, clock);
    }

    @Test
    void theWatchlistIsSeededOnceAndResolvedFromCareersUrlsAndPages() {
        // A custom careers site: readable, no ATS link. (Hand-written page, not a recording.)
        http.responses.put("https://www.instabug.com/robots.txt", new FetchResponse(404, "", null));
        http.responses.put("https://www.instabug.com/careers", FetchResponse.ok("<h1>Careers</h1><a href=\"/careers/1\">Backend</a>"));

        assertThat(seeder.seed()).isEqualTo(90);
        seeder.seed(); // idempotent
        int resolved = resolver.resolvePending();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Long.class)).isEqualTo(90);
        // Bosta, MoneyFellows, Instabug and Thndr (an Ashby URL, resolved without reading a page).
        assertThat(resolved).isEqualTo(4);
        assertThat(jdbc.queryForMap("SELECT ats_type, board_token FROM company WHERE name = 'Thndr'"))
                .containsEntry("ats_type", "ashby")
                .containsEntry("board_token", "thndr");
        assertThat(jdbc.queryForMap("SELECT ats_type, board_token FROM company WHERE name = 'Bosta'"))
                .containsEntry("ats_type", "lever")
                .containsEntry("board_token", "Bosta");
        assertThat(jdbc.queryForMap("SELECT ats_type, board_token FROM company WHERE name = 'MoneyFellows'"))
                .containsEntry("ats_type", "workable")
                .containsEntry("board_token", "moneyfellows");
        assertThat(jdbc.queryForMap("SELECT ats_type, board_token, last_resolved_at FROM company WHERE name = 'Instabug'"))
                .containsEntry("ats_type", "email-alerts")
                .containsEntry("board_token", null)
                .doesNotContainEntry("last_resolved_at", null);
        // Pages that could not be read stay unresolved and are retried on the next pass.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM company WHERE ats_type IS NULL AND last_resolved_at IS NULL", Long.class))
                .isEqualTo(86);

        // Resolved companies are not fetched again before the 30 day re-check.
        http.requested.clear();
        resolver.resolvePending();
        assertThat(http.requested).noneMatch(url -> url.contains("instabug"));
    }

    @Test
    void aResolvedLeverBoardIsIngestedLinkedToItsCompanyRoutedByAgeAndDedupedOnTheNextRun() {
        http.responses.put(LEVER_BOSTA, fixture("lever/bosta.json"));
        seeder.seed();
        resolver.resolvePending();
        // "Strategy Associate" was created 2026-11-10T11:01Z: 59 minutes old now, so hot.
        // The other two postings are days old.

        IngestionResult first = ingestion.run(connector("lever"));

        assertThat(first).isEqualTo(new IngestionResult(3, 3, 1, 0));
        assertThat(publisher.queues).containsExactlyInAnyOrder(PostingQueue.HOT, PostingQueue.BACKLOG, PostingQueue.BACKLOG);
        // Postings hang off the seeded watchlist row; no second "Bosta" company appears.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM posting p JOIN company c ON c.id = p.company_id
                        WHERE c.name = 'Bosta' AND c.ats_type = 'lever' AND p.source = 'lever'
                        """,
                        Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Long.class)).isEqualTo(90);

        clock.now = clock.now.plus(Duration.ofMinutes(15));
        IngestionResult second = ingestion.run(connector("lever"));

        assertThat(second).isEqualTo(new IngestionResult(3, 0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting", Long.class)).isEqualTo(3);
    }

    @Test
    void workableSearchResultsAreRoutedByAgeAndAJobAlreadyKnownFromAnotherSourceIsDropped() {
        http.responses.put(WORKABLE_EGYPT, fixture("workable/search-egypt.json"));
        clock.now = Instant.parse("2026-11-10T17:00:00Z");
        // The Foodics role arrived earlier through another source, under a different URL.
        ingestion.ingest("gmail", List.of(new RawPosting(
                "gmail", "m1", "Foodics", "Senior Software Engineer PAY (Back-End)", "Cairo, Egypt", "remote", "en",
                null, "platform", "https://example.com/foodics-pay", null, null)));
        publisher.queues.clear();
        clock.now = clock.now.plus(Duration.ofSeconds(1));

        IngestionResult result = ingestion.run(connector("workable"));

        // 6 found, Foodics dropped by content hash; only the 16:24Z posting is under 2 hours old.
        assertThat(result).isEqualTo(new IngestionResult(6, 5, 1, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE source = 'workable'", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM posting WHERE title = 'Senior Software Engineer PAY (Back-End)'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void remotiveIsCalledOnceThenNotAgainForSixHoursAndItsDelayedJobsGoToTheBacklog() {
        http.responses.put(REMOTIVE_FEED, fixture("remotive/remote-jobs.json"));
        ConnectorScheduler scheduler = scheduler();
        SourceConnector remotive = connector("remotive");

        assertThat(scheduler.runIfDue(remotive)).isTrue();
        assertThat(publisher.queues).hasSize(13).containsOnly(PostingQueue.BACKLOG);

        clock.now = clock.now.plus(Duration.ofHours(5));
        assertThat(scheduler.runIfDue(remotive)).isFalse();
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(scheduler.runIfDue(remotive)).isTrue();

        assertThat(http.requested).containsExactly(REMOTIVE_FEED, REMOTIVE_FEED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_run WHERE source = 'remotive'", Long.class)).isEqualTo(2);
    }

    @Test
    void remotiveGetsNoFifthCallInsideADay() {
        http.responses.put(REMOTIVE_FEED, fixture("remotive/remote-jobs.json"));
        // Four calls in the last 24 hours, the latest 7 hours ago (for example after restarts).
        for (int hoursAgo : new int[] {23, 20, 14, 7}) {
            postings.recordRun("remotive", clock.now.minus(Duration.ofHours(hoursAgo)), 13, 0, List.of());
        }

        assertThat(scheduler().runIfDue(connector("remotive"))).isFalse();
        assertThat(http.requested).isEmpty();
    }

    @Test
    void aRateLimitedSourceIsRecordedAndLeftAloneUntilItsRetryAfterHasPassed() {
        http.responses.put(WORKABLE_EGYPT, new FetchResponse(429, "{\"error\":\"rate_limit\"}", Duration.ofHours(2)));
        ConnectorScheduler scheduler = scheduler();
        SourceConnector workable = connector("workable");

        assertThat(scheduler.runIfDue(workable)).isTrue();
        assertThat(jdbc.queryForObject("SELECT errors FROM source_run WHERE source = 'workable'", String.class))
                .startsWith("rate limited");

        // The 15 minute interval has passed, the 2 hour Retry-After has not.
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        assertThat(scheduler.runIfDue(workable)).isFalse();
        assertThat(http.requested).containsExactly(WORKABLE_EGYPT);

        http.responses.put(WORKABLE_EGYPT, fixture("workable/search-egypt.json"));
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertThat(scheduler.runIfDue(workable)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE source = 'workable'", Long.class)).isEqualTo(6);
    }

    @Test
    void aFailedRunIsRecordedSoTheSourceIsNotCalledAgainBeforeItsInterval() {
        // No response registered: the fetch fails like an unreachable host.
        ConnectorScheduler scheduler = scheduler();
        SourceConnector remotive = connector("remotive");

        assertThat(scheduler.runIfDue(remotive)).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(scheduler.runIfDue(remotive)).isFalse();

        assertThat(http.requested).containsExactly(REMOTIVE_FEED);
        assertThat(jdbc.queryForObject("SELECT errors FROM source_run WHERE source = 'remotive'", String.class))
                .startsWith("SourceFetchException");
    }
}
