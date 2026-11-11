package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jobradar.notifier.CvLinkSigner;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The dashboard on the internet: access token, signed CV links, posting detail and summaries. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "jobradar.matching.listener.enabled=false",
            "jobradar.notifier.listener.enabled=false",
            "jobradar.dashboard.access-token=test-access-token-1234",
            "jobradar.dashboard.link-secret=test-link-secret-12345678",
            "jobradar.public-url=https://jobs.example.test",
            "JOBRADAR_WEB_DIR=src/test/resources/web-test/"
        })
@ActiveProfiles("dashboard")
class DashboardAccessTests extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CvLinkSigner signer;

    @MockitoBean
    S3Client s3;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    long userId;
    long postingId;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject("INSERT INTO app_user (email, daily_apply_cap) VALUES ('me@example.com', 5) RETURNING id", Long.class);
        postingId = jdbc.queryForObject(
                "INSERT INTO posting (source, source_id, title, location, description, first_seen_at, content_hash) VALUES ('fixture', 'a', 'Backend Intern', 'Cairo', 'Java', now(), 'a') RETURNING id",
                Long.class);
    }

    private HttpResponse<String> send(String path, Consumer<HttpRequest.Builder> customise) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        customise.accept(builder);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsWithoutTheTokenAreRejectedAndTheBearerTokenIsAccepted() throws Exception {
        assertThat(send("/api/dashboard/summary", b -> b.GET()).statusCode()).isEqualTo(401);
        assertThat(send("/api/dashboard/summary", b -> b.GET().header("Authorization", "Bearer wrong-token-value-xx")).statusCode()).isEqualTo(401);
        assertThat(send("/api/dashboard/summary", b -> b.GET().header("Authorization", "Bearer test-access-token-1234")).statusCode()).isEqualTo(200);
    }

    @Test
    void theTokenInTheUrlSetsACookieForLaterRequests() throws Exception {
        HttpResponse<String> first = send("/api/dashboard/summary?token=test-access-token-1234", b -> b.GET());

        assertThat(first.statusCode()).isEqualTo(200);
        String cookie = first.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("jr_token=test-access-token-1234").contains("HttpOnly");
        assertThat(send("/api/dashboard/summary", b -> b.GET().header("Cookie", "jr_token=test-access-token-1234")).statusCode()).isEqualTo(200);
    }

    @Test
    void aSignedCvLinkOpensThatOneFileOnly() throws Exception {
        String key = "cv/" + userId + "/" + postingId + "/x.pdf";
        when(s3.getObject(any(java.util.function.Consumer.class), any(ResponseTransformer.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "%PDF-1.7 test".getBytes(StandardCharsets.UTF_8)));
        String token = signer.sign(key, Duration.ofHours(1));
        String q = "k=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&t=" + token;

        HttpResponse<String> ok = send("/api/dashboard/cv?" + q, b -> b.GET());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/pdf");
        assertThat(ok.body()).startsWith("%PDF");

        // Another key with the same signature, an expired link and a tampered signature are refused.
        String other = "k=" + URLEncoder.encode("cv/9/9/y.pdf", StandardCharsets.UTF_8) + "&t=" + token;
        assertThat(send("/api/dashboard/cv?" + other, b -> b.GET()).statusCode()).isEqualTo(403);
        String expired = signer.sign(key, Duration.ofSeconds(-5));
        assertThat(send("/api/dashboard/cv?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&t=" + expired, b -> b.GET()).statusCode()).isEqualTo(403);
        assertThat(send("/api/dashboard/cv?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&t=" + token + "x", b -> b.GET()).statusCode()).isEqualTo(403);
    }

    @Test
    void postingDetailCarriesTheAtsReportAndSummariesCanBeConfirmed() throws Exception {
        jdbc.update("INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key, ats_score, summary, ats_report) VALUES (?, ?, '{1}', 'cv/1/1/x.pdf', 72.5, 'A summary.', ?::jsonb)",
                userId, postingId, "{\"score\":72.5,\"checks\":[{\"name\":\"One page\",\"ok\":true,\"detail\":\"1 page.\"}],\"missing\":[\"kubernetes\"],\"fixes\":[]}");
        long summary = jdbc.queryForObject(
                "INSERT INTO cv_summary (user_id, label, focus, text) VALUES (?, 'backend', ARRAY['java'], 'Text.') RETURNING id", Long.class, userId);

        JsonNode detail = json.readTree(send("/api/dashboard/postings/" + postingId, b -> b.GET().header("Authorization", "Bearer test-access-token-1234")).body());
        assertThat(detail.path("cv").path("atsScore").asDouble()).isEqualTo(72.5);
        assertThat(detail.path("cv").path("atsReport").path("checks").get(0).path("name").asString()).isEqualTo("One page");
        assertThat(detail.path("cv").path("pdfUrl").asString()).endsWith("/cv.pdf");

        assertThat(send("/api/dashboard/summaries", b -> b.GET().header("Authorization", "Bearer test-access-token-1234")).body()).contains("\"confirmed\":false");
        HttpResponse<String> confirm = send("/api/dashboard/summaries/" + summary + "/confirm", b -> b
                .POST(HttpRequest.BodyPublishers.ofString("{\"confirmed\":true}"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-access-token-1234"));
        assertThat(confirm.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT verify FROM cv_summary WHERE id = ?", Boolean.class, summary)).isFalse();
    }

    @Test
    void theBuiltDashboardPageIsServedWithoutAKeyWhileTheDataStaysLocked() throws Exception {
        HttpResponse<String> page = send("/", b -> b.GET());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("JobRadar test page");
        assertThat(send("/api/dashboard/matches", b -> b.GET()).statusCode()).isEqualTo(401);
    }
}
