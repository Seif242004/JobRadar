package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Without the local-dashboard profile, which is how the deployed api runs, the endpoints do not exist. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jobradar.matching.listener.enabled=false", "jobradar.notifier.listener.enabled=false"})
class DashboardAbsentTests extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void thereIsNoDashboardInTheDeployedApi() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dashboard/summary")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
