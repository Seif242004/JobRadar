package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jobradar.llm.LlmClient;
import com.jobradar.llm.LlmUnavailableException;
import com.jobradar.tailoring.CvReady;
import com.jobradar.tailoring.TailoringService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flow 3 end to end: real Postgres, real S3 semantics (LocalStack) and the real Typst binary.
 * Only the LLM is faked. The guardrail under test is "no invention": whatever the model says,
 * the PDF contains only text from the user's own profile, on exactly one page.
 */
@SpringBootTest(properties = "jobradar.tailoring.listener.enabled=false")
@ActiveProfiles("test")
class TailoringPipelineTests {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4")).withServices("s3");

    static final String BUCKET = "jobradar-test";
    static S3Client s3;

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        s3.createBucket(r -> r.bucket(BUCKET));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jobradar.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("jobradar.aws.region", LOCALSTACK::getRegion);
        registry.add("jobradar.tailoring.bucket", () -> BUCKET);
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
    }

    @MockitoBean
    LlmClient llm;

    @Autowired
    TailoringService tailoring;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    long userId;
    /** Every string the profile contains: the only text a CV may show. */
    Set<String> profileWords;
    List<Long> confirmedBulletIds;
    long verifyBulletId;

    // ------------------------------------------------------------------------------ fixture

    @BeforeEach
    void seedProfile() {
        jdbc.execute("TRUNCATE app_user, company, posting RESTART IDENTITY CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, daily_apply_cap) VALUES ('fixture@example.com', 5) RETURNING id",
                Long.class);
        String cv = """
                {"name":"Fixture Person","headline":"B.Sc. Computer Engineering · Example University",
                 "contacts":[{"text":"Cairo, Egypt","url":null},{"text":"fixture@example.com","url":"mailto:fixture@example.com"}],
                 "education":[{"title":"B.Sc. Computer Engineering · Example University","dates":"2022 – Present","context":"Expected Graduation: 2027"}],
                 "languages":"Arabic (Native), English (Fluent)"}""";
        jdbc.update("INSERT INTO profile (user_id, cv_static) VALUES (?, ?::jsonb)", userId, cv);

        // Three jobs and five projects with six bullets each: far more than fits on one page.
        int order = 0;
        List<String> allText = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            long entry = entry("experience", "Engineer " + i + " · Company " + i, "2025 – 2026", "Team " + i, order++);
            for (int b = 1; b <= 6; b++) {
                allText.add(bullet("experience", entry,
                        "Delivered Java service " + i + b + " with Spring Boot and PostgreSQL, serving partner traffic reliably", false));
            }
        }
        for (int i = 1; i <= 5; i++) {
            long entry = entry("project", "Project " + i + " · Stack " + i, "2025", null, order++);
            for (int b = 1; b <= 6; b++) {
                allText.add(bullet("project", entry,
                        "Implemented module " + i + b + " using C++ and OpenGL with shaders and profiling for rendering speed", false));
            }
        }
        long skills = entry("skill", "Skills", null, null, order);
        allText.add(bullet("skill", skills, "Languages: Java, C++, Python, SQL, TypeScript", false));
        // An unconfirmed claim: present in the profile, never allowed on a CV.
        verifyBulletId = jdbc.queryForObject(
                "INSERT INTO bullet (user_id, section, text, tags, entry_id) VALUES (?, 'experience', ?, ARRAY['verify'], ?) RETURNING id",
                Long.class, userId, "Unverified claim about quantum databases", entry("experience", "Old Job", "2020", null, 99));

        confirmedBulletIds = jdbc.queryForList(
                "SELECT id FROM bullet WHERE user_id = ? AND NOT ('verify' = ANY (tags)) ORDER BY id", Long.class, userId);
        profileWords = new HashSet<>(words(cv + String.join(" ", allText)));
        jdbc.queryForList("SELECT title || ' ' || coalesce(dates, '') || ' ' || coalesce(context, '') FROM cv_entry WHERE user_id = ?",
                        String.class, userId)
                .forEach(text -> profileWords.addAll(words(text)));
        // Fixed section headings of the template; the only words the renderer itself adds.
        profileWords.addAll(Set.of("education", "experience", "projects", "technical", "skills", "languages"));
    }

    private long entry(String section, String title, String dates, String context, int order) {
        return jdbc.queryForObject(
                "INSERT INTO cv_entry (user_id, section, slug, title, dates, context, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, userId, section, title + "|" + dates + "|" + order, title, dates, context, order);
    }

    private String bullet(String section, long entryId, String text, boolean verify) {
        jdbc.update("INSERT INTO bullet (user_id, section, text, tags, entry_id) VALUES (?, ?, ?, ?::text[], ?)",
                userId, section, text, verify ? "{verify}" : "{}", entryId);
        return text;
    }

    private long posting(String name) throws IOException {
        JsonNode node;
        try (InputStream in = getClass().getResourceAsStream("/golden-postings/" + name + ".json")) {
            node = json.readTree(in);
        }
        long company = jdbc.queryForObject(
                "INSERT INTO company (name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                Long.class, node.path("company").asString());
        return jdbc.queryForObject(
                "INSERT INTO posting (company_id, source, source_id, title, location, description, content_hash) VALUES (?, 'fixture', ?, ?, ?, ?, ?) RETURNING id",
                Long.class, company, name, node.path("title").asString(), node.path("location").asString(),
                node.path("description").asString(), name);
    }

    static Stream<String> goldenPostings() {
        return Stream.of("backend-java", "frontend-react", "graphics-cpp", "data-engineer", "sparse-posting");
    }

    // ------------------------------------------------------------------------------ tests

    @ParameterizedTest
    @MethodSource("goldenPostings")
    void whateverTheModelAnswersTheCvHasOnlyProfileTextOnOnePage(String golden) throws IOException {
        long postingId = posting(golden);
        // The model asks for every confirmed bullet: too many for one page, so the fit loop must trim.
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds));

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        assertOnePageWithOnlyProfileText(ready);
        List<Long> stored = storedBulletIds(ready);
        assertThat(stored).isNotEmpty().isSubsetOf(confirmedBulletIds).doesNotHaveDuplicates();
        assertThat(stored).hasSizeLessThan(confirmedBulletIds.size());
        // The balancer spreads the model's picks over every entry; trimming then drops from the end,
        // so the stored list is a prefix of the balanced order.
        List<com.jobradar.tailoring.internal.ProfileBullet> all = jdbc.query(
                "SELECT id, section, text, entry_id FROM bullet WHERE user_id = ? ORDER BY id",
                (rs, i) -> new com.jobradar.tailoring.internal.ProfileBullet(
                        rs.getLong("id"), rs.getString("section"), rs.getString("text"), false,
                        rs.getObject("entry_id") == null ? null : rs.getLong("entry_id")),
                userId);
        List<Long> balanced = com.jobradar.tailoring.internal.BulletBalancer.balance(confirmedBulletIds, all);
        assertThat(stored).isEqualTo(balanced.subList(0, stored.size()));
    }

    @Test
    void anInventedBulletIdIsRejectedAndReplacedByTheEmbeddingOrder() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn("{\"bullet_ids\": [" + confirmedBulletIds.get(0) + ", 999999]}");

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        assertOnePageWithOnlyProfileText(ready);
        assertThat(storedBulletIds(ready)).doesNotContain(999999L).isSubsetOf(confirmedBulletIds);
    }

    @Test
    void anUnconfirmedBulletNeverReachesTheCv() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(List.of(confirmedBulletIds.get(0), verifyBulletId)));

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        assertThat(storedBulletIds(ready)).doesNotContain(verifyBulletId);
        assertThat(pdfText(ready)).doesNotContain("quantum");
    }

    @Test
    void anUnavailableModelFallsBackToTheEmbeddingOrder() throws IOException {
        long postingId = posting("graphics-cpp");
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("budget spent"));

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        assertOnePageWithOnlyProfileText(ready);
    }

    @Test
    void garbageFromTheModelFallsBack() throws IOException {
        long postingId = posting("data-engineer");
        when(llm.complete(anyString(), anyString())).thenReturn("I think you should rewrite your summary!");

        assertOnePageWithOnlyProfileText(tailoring.tailor(userId, postingId).orElseThrow());
    }

    @Test
    void aRedeliveredRequestReturnsTheExistingCv() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds.subList(0, 6)));

        CvReady first = tailoring.tailor(userId, postingId).orElseThrow();
        CvReady second = tailoring.tailor(userId, postingId).orElseThrow();

        assertThat(second.cvVersionId()).isEqualTo(first.cvVersionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_version", Integer.class)).isEqualTo(1);
    }

    @Test
    void aUserWithoutCvDataGetsNoCv() throws IOException {
        long postingId = posting("backend-java");
        jdbc.update("UPDATE profile SET cv_static = NULL WHERE user_id = ?", userId);

        assertThat(tailoring.tailor(userId, postingId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_version", Integer.class)).isZero();
    }

    @Test
    void aMissingPostingGetsNoCv() {
        assertThat(tailoring.tailor(userId, 123456L)).isEmpty();
    }

    @Test
    void theAtsScoreReflectsThePostingKeywords() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds.subList(0, 6)));

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        // The first bullets mention java, spring, boot and postgresql, which the posting repeats.
        assertThat(ready.atsScore()).isBetween(1.0, 100.0);
        assertThat(jdbc.queryForObject("SELECT ats_score FROM cv_version WHERE id = ?", Double.class, ready.cvVersionId()))
                .isEqualTo(ready.atsScore());
    }

    @Test
    void aConfirmedSummaryAppearsOnTheCvAndAnUnconfirmedOneNever() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds.subList(0, 6)));
        // Only words that the profile contains, so the CV still passes the profile-text check.
        jdbc.update("INSERT INTO cv_summary (user_id, label, focus, text, verify, sort_order) VALUES (?, 'java', ARRAY['java','spring'], ?, false, 0)",
                userId, "Computer Engineering student building Java services with Spring Boot and PostgreSQL.");
        jdbc.update("INSERT INTO cv_summary (user_id, label, focus, text, verify, sort_order) VALUES (?, 'draft', ARRAY['java'], ?, true, 1)",
                userId, "Unconfirmed draft about quantum databases.");

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        profileWords.add("summary");
        assertOnePageWithOnlyProfileText(ready);
        assertThat(pdfText(ready)).contains("Computer Engineering student building Java services").doesNotContain("quantum");
        assertThat(jdbc.queryForObject("SELECT summary FROM cv_version WHERE id = ?", String.class, ready.cvVersionId()))
                .startsWith("Computer Engineering student");
    }

    @Test
    void aSummaryWithAnInventedTermIsSkippedNotPrinted() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds.subList(0, 6)));
        jdbc.update("INSERT INTO cv_summary (user_id, label, focus, text, verify, sort_order) VALUES (?, 'bad', ARRAY['java'], ?, false, 0)",
                userId, "Engineer who led Kubernetes migrations at Google.");

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        assertThat(pdfText(ready)).doesNotContain("Kubernetes");
        assertThat(jdbc.queryForObject("SELECT summary FROM cv_version WHERE id = ?", String.class, ready.cvVersionId())).isNull();
    }

    @Test
    void theAtsReportIsStoredWithChecksAndKeywords() throws IOException {
        long postingId = posting("backend-java");
        when(llm.complete(anyString(), anyString())).thenReturn(selection(confirmedBulletIds.subList(0, 6)));

        CvReady ready = tailoring.tailor(userId, postingId).orElseThrow();

        JsonNode report = json.readTree(jdbc.queryForObject("SELECT ats_report::text FROM cv_version WHERE id = ?", String.class, ready.cvVersionId()));
        assertThat(report.path("score").asDouble()).isEqualTo(ready.atsScore());
        assertThat(report.path("checks")).extracting(c -> c.path("name").asString())
                .contains("Selectable text", "Contact details", "Standard headings", "One page");
        assertThat(report.path("checks").get(0).path("ok").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------------------ helpers

    private static String selection(List<Long> ids) {
        return "{\"bullet_ids\": " + ids + "}";
    }

    @SuppressWarnings("unchecked")
    private List<Long> storedBulletIds(CvReady ready) {
        return jdbc.queryForObject("SELECT bullet_ids FROM cv_version WHERE id = ?", (rs, n) -> {
            try {
                Array array = rs.getArray(1);
                return List.of((Long[]) array.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }, ready.cvVersionId());
    }

    private byte[] pdfBytes(CvReady ready) {
        String key = jdbc.queryForObject("SELECT pdf_s3_key FROM cv_version WHERE id = ?", String.class, ready.cvVersionId());
        assertThat(key).startsWith("cv/" + userId + "/" + ready.postingId() + "/");
        return s3.getObject(r -> r.bucket(BUCKET).key(key), ResponseTransformer.toBytes()).asByteArray();
    }

    private String pdfText(CvReady ready) {
        try (PDDocument document = Loader.loadPDF(pdfBytes(ready))) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertOnePageWithOnlyProfileText(CvReady ready) {
        try (PDDocument document = Loader.loadPDF(pdfBytes(ready))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Set<String> unknown = new HashSet<>(words(pdfText(ready)));
        unknown.removeAll(profileWords);
        assertThat(unknown).as("words on the CV that are not in the profile").isEmpty();
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Lower-case alphanumeric words; punctuation and layout are irrelevant to the check. */
    private static List<String> words(String text) {
        return WORD.matcher(text.toLowerCase(Locale.ROOT)).results().map(m -> m.group()).collect(Collectors.toList());
    }
}
