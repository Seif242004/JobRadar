package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The local dashboard's read-only endpoints, over real HTTP against a real database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jobradar.matching.listener.enabled=false", "jobradar.notifier.listener.enabled=false"})
@ActiveProfiles("local-dashboard")
class DashboardTests extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    long userId;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, daily_apply_cap) VALUES ('me@example.com', 5) RETURNING id", Long.class);
    }

    private long posting(String title, double fit, double priority, boolean eligible) {
        long id = jdbc.queryForObject(
                "INSERT INTO posting (source, source_id, title, location, first_seen_at, content_hash) VALUES ('fixture', ?, ?, 'Cairo', now(), ?) RETURNING id",
                Long.class, title, title, title);
        jdbc.update(
                "INSERT INTO match (user_id, posting_id, eligible, fit_score, priority, band, reasons) VALUES (?, ?, ?, ?, ?, 'hot', 'why')",
                userId, id, eligible, fit, priority);
        return id;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    void matchesAreEligibleOnlyAndNewestFirstWithCvFlag() throws Exception {
        long best = posting("Best", 92, 90, true);
        long middle = posting("Middle", 70, 60, true);
        // The list is chronological: the older posting comes second whatever its score.
        jdbc.update("UPDATE posting SET first_seen_at = now() - INTERVAL '1 hour' WHERE id = ?", middle);
        posting("Rejected", 99, 99, false);
        jdbc.update("INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key) VALUES (?, ?, '{1}', 'k')", userId, best);

        JsonNode matches = get("/api/dashboard/matches");

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).path("title").asString()).isEqualTo("Best");
        assertThat(matches.get(0).path("cvReady").asBoolean()).isTrue();
        assertThat(matches.get(1).path("title").asString()).isEqualTo("Middle");
        assertThat(matches.get(1).path("cvReady").asBoolean()).isFalse();
    }

    @Test
    void summaryAndApplicationsReflectTheTracker() throws Exception {
        long id = posting("Applied", 90, 80, true);
        long cv = jdbc.queryForObject(
                "INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key) VALUES (?, ?, '{1}', 'k') RETURNING id",
                Long.class, userId, id);
        jdbc.update(
                "INSERT INTO application (user_id, posting_id, cv_version_id, channel, status, sent_at) VALUES (?, ?, ?, 'email', 'replied', ?)",
                userId, id, cv, Timestamp.from(Instant.now()));

        JsonNode summary = get("/api/dashboard/summary");
        JsonNode applications = get("/api/dashboard/applications");

        assertThat(summary.path("email").asString()).isEqualTo("me@example.com");
        assertThat(summary.path("applications").asInt()).isEqualTo(1);
        assertThat(summary.path("replies").asInt()).isEqualTo(1);
        assertThat(summary.path("hotOrWarmWithCv").asInt()).isEqualTo(1);
        assertThat(applications).hasSize(1);
        assertThat(applications.get(0).path("status").asString()).isEqualTo("REPLIED");
    }

    @Test
    void theApiOffersNoWriteEndpoints() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dashboard/applications"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void preferencesAreSavedAndMarkedOnboarded() throws Exception {
        jdbc.update("INSERT INTO profile (user_id) VALUES (?)", userId);
        assertThat(get("/api/dashboard/preferences").path("onboarded").asBoolean()).isFalse();

        String body = """
                {"roles":["Backend"],"cities":["Cairo"],"remote":true,"jobTypes":["internship","full-time"],
                 "entryLevelOnly":true,"maxYearsExperience":1,"titleKeywords":["backend","ai"],
                 "excludeKeywords":["sales"],"maxAgeDays":3,"pushMinFit":80,"digestMinFit":50,"onboarded":false}""";
        HttpResponse<String> saved = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dashboard/preferences"))
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(saved.statusCode()).isEqualTo(200);

        JsonNode prefs = get("/api/dashboard/preferences");
        assertThat(prefs.path("onboarded").asBoolean()).isTrue();
        assertThat(prefs.path("maxYearsExperience").asInt()).isEqualTo(1);
        assertThat(prefs.path("maxAgeDays").asInt()).isEqualTo(3);
        assertThat(prefs.path("pushMinFit").asInt()).isEqualTo(80);
        assertThat(prefs.path("titleKeywords")).hasSize(2);
        assertThat(prefs.path("excludeKeywords").get(0).asString()).isEqualTo("sales");
        assertThat(prefs.path("remote").asBoolean()).isTrue();
    }
}
