package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.IngestionResult;
import com.jobradar.ingestion.IngestionService;
import com.jobradar.ingestion.RawPosting;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Flow 1 end to end against real Postgres + pgvector and real SQS semantics (LocalStack):
 * store, dedupe, route to the hot or backlog queue, record the run.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionPipelineTests {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // Same pinned image as docker-compose.yml.
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4")).withServices("sqs");

    static SqsClient sqs;
    static String hotUrl;
    static String backlogUrl;

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        hotUrl = sqs.createQueue(r -> r.queueName("jobradar-hot")).queueUrl();
        backlogUrl = sqs.createQueue(r -> r.queueName("jobradar-backlog")).queueUrl();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobradar.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("jobradar.aws.region", LOCALSTACK::getRegion);
        registry.add("jobradar.ingestion.queues.hot-url", () -> hotUrl);
        registry.add("jobradar.ingestion.queues.backlog-url", () -> backlogUrl);
        // The SDK's default credential chain reads these system properties.
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
    }

    @Autowired
    IngestionService ingestion;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE posting, company, source_run CASCADE");
        sqs.purgeQueue(r -> r.queueUrl(hotUrl));
        sqs.purgeQueue(r -> r.queueUrl(backlogUrl));
    }

    private static RawPosting posting(String source, String id, String title, String applyUrl, Instant postedAt) {
        return new RawPosting(
                source, id, "Instabug", title, "Cairo, Egypt", "hybrid", "en", "Description",
                "ats_form", applyUrl, null, postedAt);
    }

    @Test
    void freshPostingsGoToTheHotQueueAndOldOnesToTheBacklog() {
        Instant now = Instant.now();
        IngestionResult result = ingestion.ingest("greenhouse", List.of(
                posting("greenhouse", "1", "Backend Engineer", "https://example.com/jobs/1", now.minus(Duration.ofMinutes(30))),
                posting("greenhouse", "2", "Data Engineer", "https://example.com/jobs/2", now.minus(Duration.ofDays(3))),
                // No posted_at: fresh on its first sighting.
                posting("greenhouse", "3", "QA Engineer", "https://example.com/jobs/3", null)));

        assertThat(result).isEqualTo(new IngestionResult(3, 3, 2, 0));
        assertThat(drain(hotUrl)).hasSize(2).allSatisfy(body -> assertThat(body).contains("\"postingId\""));
        assertThat(drain(backlogUrl)).hasSize(1);
    }

    @Test
    void reRunningTheSameSourceStoresAndPublishesNothingNew() {
        List<RawPosting> batch =
                List.of(posting("greenhouse", "1", "Backend Engineer", "https://example.com/jobs/1", null));
        ingestion.ingest("greenhouse", batch);
        drain(hotUrl);

        IngestionResult second = ingestion.ingest("greenhouse", batch);

        assertThat(second).isEqualTo(new IngestionResult(1, 0, 0, 0));
        assertThat(drain(hotUrl)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting", Long.class)).isEqualTo(1);
        // first_seen_at is kept, so on later runs the posting's age counts from the first sighting.
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT first_seen_at) FROM posting", Long.class)).isEqualTo(1);
    }

    @Test
    void theSameJobFromASecondSourceIsDroppedByApplyUrlOrContentHash() {
        ingestion.ingest("greenhouse", List.of(
                posting("greenhouse", "1", "Backend Engineer", "https://example.com/jobs/1", null)));

        IngestionResult second = ingestion.ingest("workable", List.of(
                // Same apply URL (tracking parameter aside), different title.
                posting("workable", "A", "Backend Engineer II", "https://example.com/jobs/1?utm_source=x", null),
                // Different URL, same company + title + location.
                posting("workable", "B", "backend engineer", "https://other.example.com/b", null),
                // A different job.
                posting("workable", "C", "Mobile Engineer", "https://other.example.com/c", null)));

        assertThat(second.newItems()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT title FROM posting ORDER BY id", String.class))
                .containsExactly("Backend Engineer", "Mobile Engineer");
    }

    @Test
    void aSharedHrMailboxIsNotTreatedAsADuplicate() {
        RawPosting first = new RawPosting(
                "gmail", "m1", "Acme", "Backend Intern", "Cairo", null, "en", null, "email", "hr@acme.example", null, null);
        RawPosting second = new RawPosting(
                "gmail", "m2", "Acme", "Frontend Intern", "Cairo", null, "en", null, "email", "hr@acme.example", null, null);

        assertThat(ingestion.ingest("gmail", List.of(first, second)).newItems()).isEqualTo(2);
    }

    @Test
    void everyRunIsRecordedAndCompaniesAreShared() {
        ingestion.ingest("greenhouse", List.of(
                posting("greenhouse", "1", "Backend Engineer", "https://example.com/jobs/1", null),
                posting("greenhouse", "2", "Data Engineer", "https://example.com/jobs/2", null)));

        assertThat(jdbc.queryForMap("SELECT items_found, new_items, errors FROM source_run"))
                .containsEntry("items_found", 2)
                .containsEntry("new_items", 2)
                .containsEntry("errors", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Long.class)).isEqualTo(1);
    }

    /** Reads everything currently on the queue. */
    private static List<String> drain(String queueUrl) {
        return sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1))
                .messages().stream().map(Message::body).toList();
    }
}
