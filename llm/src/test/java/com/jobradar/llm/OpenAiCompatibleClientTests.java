package com.jobradar.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Runs the client against a local stub server; no live network calls. */
class OpenAiCompatibleClientTests {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    /** Maps a request body to {status, response body}. */
    private Function<String, Object[]> responder;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Object[] reply = responder.apply(body);
            byte[] bytes = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders((int) reply[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleClient client(String key, int dailyCap, Clock clock) {
        AiProperties properties = new AiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                key, "primary-model", List.of("backup-model"), "embed-model", dailyCap, Duration.ofSeconds(5));
        return new OpenAiCompatibleClient(properties, JsonMapper.builder().build(), clock);
    }

    private static String chatReply(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void completeSendsBothMessagesAndReturnsTheReplyText() {
        responder = body -> new Object[] {200, chatReply("hello")};

        String reply = client("secret", 10, Clock.systemUTC()).complete("be brief", "say hello");

        assertThat(reply).isEqualTo("hello");
        assertThat(authHeaders).containsExactly("Bearer secret");
        assertThat(requestBodies.get(0)).contains("\"primary-model\"", "be brief", "say hello");
    }

    @Test
    void aRetiredModelFallsBackToTheNextOne() {
        responder = body -> body.contains("primary-model")
                ? new Object[] {404, "404 page not found"}
                : new Object[] {200, chatReply("from backup")};

        assertThat(client("secret", 10, Clock.systemUTC()).complete("s", "u")).isEqualTo("from backup");
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void otherProviderErrorsAreNotRetriedOnAnotherModel() {
        responder = body -> new Object[] {429, "{\"error\":\"rate limited\"}"};

        assertThatThrownBy(() -> client("secret", 10, Clock.systemUTC()).complete("s", "u"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage("AI provider answered 429");
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void theDailyCapStopsCallsAndResetsOnTheNextUtcDay() {
        responder = body -> new Object[] {200, chatReply("ok")};
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T23:00:00Z"));
        OpenAiCompatibleClient client = client("secret", 2, clock);

        client.complete("s", "u");
        client.complete("s", "u");
        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("cap of 2 reached");
        // The third call never reached the provider.
        assertThat(requestBodies).hasSize(2);

        clock.now = Instant.parse("2026-11-11T00:00:01Z");
        assertThat(client.complete("s", "u")).isEqualTo("ok");
        assertThat(client.callsToday()).isEqualTo(1);
    }

    @Test
    void withoutAKeyNothingIsSent() {
        responder = body -> new Object[] {200, chatReply("ok")};
        OpenAiCompatibleClient client = client("", 10, Clock.systemUTC());

        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmUnavailableException.class);
        assertThatThrownBy(() -> client.embed(List.of("text"))).isInstanceOf(LlmUnavailableException.class);
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void embedReturnsOneVectorPerInputInInputOrder() {
        // The stub answers out of order on purpose: index 1 first.
        responder = body -> new Object[] {
            200, "{\"data\":[{\"index\":1,\"embedding\":" + vector(0.5) + "},{\"index\":0,\"embedding\":" + vector(0.25) + "}]}"
        };

        List<float[]> vectors = client("secret", 10, Clock.systemUTC()).embed(List.of("first", "second"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(EmbeddingClient.DIMENSIONS).containsOnly(0.25f);
        assertThat(vectors.get(1)).containsOnly(0.5f);
        assertThat(requestBodies.get(0)).contains("\"embed-model\"", "first", "second");
    }

    @Test
    void anEmbeddingModelWithTheWrongDimensionIsRejected() {
        responder = body -> new Object[] {200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"};

        assertThatThrownBy(() -> client("secret", 10, Clock.systemUTC()).embed(List.of("text")))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("3 dimensions, the schema expects 1024");
    }

    private static String vector(double value) {
        return "[" + (value + ",").repeat(EmbeddingClient.DIMENSIONS - 1) + value + "]";
    }

    /** A clock whose time the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
