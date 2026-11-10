package com.jobradar.notifier.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.notifier.internal.TelegramClient.Button;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Bot API is replaced by a local server; no real network call. */
class HttpTelegramClientTests {

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private volatile String updatesReply = "{\"ok\":true,\"result\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botTOKEN/", exchange -> {
            String method = exchange.getRequestURI().getPath().substring("/botTOKEN/".length());
            bodies.put(method, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("getUpdates".equals(method) ? updatesReply : "{\"ok\":true,\"result\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpTelegramClient client(String token) {
        return new HttpTelegramClient(JsonMapper.builder().build(), token, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void sendsLinkAndCallbackButtonsInRows() {
        client("TOKEN").sendWithButtons(42, "Hello", List.of(
                List.of(Button.link("Open posting", "https://example.com/j")),
                List.of(Button.action("Skip", "sk:5"), Button.action("Later", "lt:5"))));

        assertThat(bodies.get("sendMessage"))
                .contains("\"chat_id\":42", "\"text\":\"Hello\"", "\"url\":\"https://example.com/j\"", "\"callback_data\":\"sk:5\"");
    }

    @Test
    void readsCallbackTapsAndAdvancesTheOffsetPastEveryUpdate() {
        updatesReply = """
                {"ok":true,"result":[
                  {"update_id":10,"message":{"message_id":1,"chat":{"id":42}}},
                  {"update_id":11,"callback_query":{"id":"cb1","from":{"id":42},"data":"ap:5:9",
                     "message":{"message_id":77,"chat":{"id":42}}}}
                ]}""";

        TelegramClient.Updates updates = client("TOKEN").getUpdates(0, 1);

        assertThat(updates.nextOffset()).isEqualTo(12);
        assertThat(updates.taps()).containsExactly(new TelegramClient.Tap(11, "cb1", 42, 42, 77, "ap:5:9"));
        assertThat(bodies.get("getUpdates")).contains("\"allowed_updates\":[\"callback_query\"]");
    }

    @Test
    void withoutATokenNothingIsCalled() {
        HttpTelegramClient client = client("");

        client.sendWithButtons(42, "Hello", List.of());
        client.answerTap("cb1", "ok");
        client.clearButtons(42, 1);

        assertThat(client.getUpdates(7, 1).nextOffset()).isEqualTo(7);
        assertThat(bodies).isEmpty();
    }
}
