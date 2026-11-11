package com.jobradar.notifier.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs against a loopback server; nothing here reaches ntfy.sh. */
class NtfyClientTests {

    private HttpServer server;
    private final List<String> publishedBodies = new ArrayList<>();
    private volatile String tapsReply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] reply = new byte[0];
            if (exchange.getRequestMethod().equals("POST")) {
                publishedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else {
                reply = tapsReply.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) {
                exchange.getResponseBody().write(reply);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NtfyClient client() {
        return new NtfyClient(JsonMapper.builder().build(), "http://127.0.0.1:" + server.getAddress().getPort(), "secret-topic", 7);
    }

    @Test
    void publishesTitleMessageAndAtMostThreeButtons() throws Exception {
        client().sendWithButtons(1, "Apply now: Java Intern\nAcme, Cairo", List.of(
                List.of(TelegramClient.Button.link("Open posting", "https://example.com/j")),
                List.of(TelegramClient.Button.action("Approve & send", "a:1:2"),
                        TelegramClient.Button.action("Skip", "s:1"),
                        TelegramClient.Button.action("Later", "l:1"))));

        JsonNode body = JsonMapper.builder().build().readTree(publishedBodies.get(0));
        assertThat(body.path("topic").asText()).isEqualTo("secret-topic");
        assertThat(body.path("title").asText()).isEqualTo("Apply now: Java Intern");
        assertThat(body.path("message").asText()).isEqualTo("Acme, Cairo");
        assertThat(body.path("actions")).hasSize(3);
        assertThat(body.path("actions").get(0).path("action").asText()).isEqualTo("view");
        JsonNode approve = body.path("actions").get(1);
        assertThat(approve.path("action").asText()).isEqualTo("http");
        assertThat(approve.path("url").asText()).endsWith("/secret-topic-taps");
        assertThat(approve.path("body").asText()).isEqualTo("a:1:2");
    }

    @Test
    void readsTapsFromTheCompanionTopicAsTapsFromTheConfiguredUser() {
        tapsReply = "{\"id\":\"x1\",\"time\":1000,\"event\":\"open\",\"topic\":\"secret-topic-taps\"}\n"
                + "{\"id\":\"x2\",\"time\":1001,\"event\":\"message\",\"topic\":\"secret-topic-taps\",\"message\":\"s:1\"}\n";

        TelegramClient.Updates updates = client().getUpdates(900, 1);

        assertThat(updates.taps()).hasSize(1);
        TelegramClient.Tap tap = updates.taps().get(0);
        assertThat(tap.data()).isEqualTo("s:1");
        assertThat(tap.fromId()).isEqualTo(7);
        assertThat(tap.chatId()).isEqualTo(7);
        assertThat(updates.nextOffset()).isEqualTo(1002);
    }
}
