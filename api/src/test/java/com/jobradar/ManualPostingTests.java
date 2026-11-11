package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Adding a posting by hand and pasting the full text of an alert-only one. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jobradar.matching.listener.enabled=false", "jobradar.notifier.listener.enabled=false"})
@ActiveProfiles("local-dashboard")
class ManualPostingTests extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        jdbc.update("INSERT INTO app_user (email, daily_apply_cap) VALUES ('me@example.com', 5)");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void textOnlyPostingIsStoredPendingWithTitleFromFirstLine() throws Exception {
        String text = "Backend Intern at Acme\\nBuild APIs with Java and Spring Boot. Send your CV to jobs@acme.com";
        HttpResponse<String> response = post("/api/dashboard/postings", "{\"text\":\"" + text + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT title || '|' || intake_pending FROM posting WHERE source = 'manual'", String.class))
                .isEqualTo("Backend Intern at Acme|true");
    }

    @Test
    void samePostingTwiceIsNotDuplicated() throws Exception {
        String body = "{\"url\":\"https://example.com/jobs/1\",\"title\":\"Intern\",\"company\":\"Acme\"}";
        post("/api/dashboard/postings", body);
        HttpResponse<String> again = post("/api/dashboard/postings", body);

        assertThat(again.body()).contains("\"duplicate\":true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting", Integer.class)).isEqualTo(1);
    }

    @Test
    void emptyInputIsRejected() throws Exception {
        assertThat(post("/api/dashboard/postings", "{\"text\":\"too short\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void pastedDescriptionReplacesTextAndQueuesRescore() throws Exception {
        long id = jdbc.queryForObject(
                "INSERT INTO posting (source, source_id, title, first_seen_at, content_hash, description)"
                        + " VALUES ('gmail', 'a', 'Intern', now(), 'h', 'snippet') RETURNING id",
                Long.class);
        String text = "Full description of the internship role with many technical requirements and details.";

        HttpResponse<String> response = post("/api/dashboard/postings/" + id + "/description", "{\"text\":\"" + text + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT description FROM posting WHERE id = ?", String.class, id)).isEqualTo(text);
        assertThat(jdbc.queryForObject("SELECT intake_pending FROM posting WHERE id = ?", Boolean.class, id)).isTrue();
    }
}
