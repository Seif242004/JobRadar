package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.ingestion.internal.source.SourceRateLimitedException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Talks to a Gmail stand-in on a loopback port: real HTTP, no internet. */
class HttpGmailApiTests {

    private HttpServer server;
    private HttpGmailApi api;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int tokenStatus = 200;
    private volatile int listStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requests.add(exchange.getRequestMethod() + " /token " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, tokenStatus, "{\"access_token\":\"AT-1\"}", null);
        });
        server.createContext("/gmail/v1/users/me/labels", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\"},{\"id\":\"Label_7\",\"name\":\"JobRadar\"}]}", null);
        });
        server.createContext("/gmail/v1/users/me/messages", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (exchange.getRequestURI().getPath().endsWith("/messages")) {
                reply(exchange, listStatus, "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t\"},{\"id\":\"m2\"}]}", listStatus == 429 ? "120" : null);
            } else {
                String raw = Base64.getUrlEncoder().withoutPadding().encodeToString("Subject: x\r\n\r\nbody".getBytes(StandardCharsets.UTF_8));
                reply(exchange, 200, "{\"id\":\"m1\",\"internalDate\":\"1794468600000\",\"raw\":\"" + raw + "\"}", null);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new HttpGmailApi(
                new GmailAlertProperties(true, "JobRadar", 3, 50, "cid", "secret", base + "/token", base + "/gmail/v1"),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void exchangesTheRefreshTokenForAnAccessToken() {
        assertThat(api.accessToken("RT-1")).isEqualTo("AT-1");

        assertThat(requests).singleElement().satisfies(request -> assertThat(request)
                .contains("POST /token")
                .contains("grant_type=refresh_token", "refresh_token=RT-1", "client_id=cid", "client_secret=secret"));
    }

    @Test
    void aRevokedRefreshTokenIsAnAccessProblemNotACrash() {
        tokenStatus = 400;

        assertThatThrownBy(() -> api.accessToken("RT-1")).isInstanceOf(GmailAccessException.class);
    }

    @Test
    void findsTheLabelByNameAndListsOnlyThatLabelsMessages() {
        assertThat(api.labelId("AT-1", "jobradar")).contains("Label_7");
        assertThat(api.labelId("AT-1", "Missing")).isEmpty();
        assertThat(api.messageIds("AT-1", "Label_7", 3, 50)).containsExactly("m1", "m2");

        assertThat(requests).anySatisfy(request -> assertThat(request)
                .contains("GET /gmail/v1/users/me/messages?")
                .contains("labelIds=Label_7", "newer_than%3A3d", "maxResults=50"));
        assertThat(requests).anySatisfy(request -> assertThat(request).contains("auth=Bearer AT-1"));
    }

    @Test
    void fetchesOneMessageInRawFormAndDecodesIt() {
        GmailApi.RawMessage message = api.message("AT-1", "m1");

        assertThat(new String(message.rfc822(), StandardCharsets.UTF_8)).endsWith("body");
        assertThat(message.receivedAt()).isEqualTo(Instant.ofEpochMilli(1794468600000L));
        assertThat(requests).last().asString().contains("GET /gmail/v1/users/me/messages/m1?format=raw");
    }

    @Test
    void neverMakesAnythingButReadRequests() {
        api.accessToken("RT-1");
        api.labelId("AT-1", "JobRadar");
        api.messageIds("AT-1", "Label_7", 3, 50);
        api.message("AT-1", "m1");

        // Only the token exchange is a POST; everything on Gmail is a GET, and nothing touches threads, drafts, send or modify.
        assertThat(requests).filteredOn(request -> request.startsWith("POST")).hasSize(1).allMatch(request -> request.contains("/token"));
        assertThat(requests).noneMatch(request -> request.matches(".*(modify|send|trash|delete|threads|drafts|batch).*"));
    }

    @Test
    void aRateLimitFromGmailCarriesItsRetryAfter() {
        listStatus = 429;

        assertThatThrownBy(() -> api.messageIds("AT-1", "Label_7", 3, 50))
                .isInstanceOfSatisfying(SourceRateLimitedException.class, e -> assertThat(e.retryAfter()).hasSeconds(120));
    }
}
