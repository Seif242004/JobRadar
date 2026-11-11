package com.jobradar.apply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Google's token and Gmail endpoints are replaced by a local server; no real network call. */
class HttpGmailSenderTests {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, int[]> tokenStatus = body -> new int[] {200};
    private volatile int sendStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("token " + body);
            int status = tokenStatus.apply(body)[0];
            byte[] reply = (status == 200 ? "{\"access_token\":\"at-123\"}" : "{\"error\":\"invalid_grant\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/gmail/users/me/messages/send", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("send " + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            exchange.sendResponseHeaders(sendStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpGmailSender sender(String clientId) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        GoogleProperties properties = new GoogleProperties(clientId, "client-secret", base + "/token", base + "/gmail");
        return new HttpGmailSender(ref -> "refresh-for-" + ref, properties, JsonMapper.builder().build());
    }

    private static OutgoingEmail email() {
        return new OutgoingEmail("me@example.com", "hr@example.com", "Application: Straße Ü", "Hello", "CV.pdf", new byte[] {'%', 'P', 'D', 'F'});
    }

    @Test
    void exchangesTheRefreshTokenAndSendsAMimeMessageWithTheAttachment() {
        sender("client-id").send("secret/ref", email());

        assertThat(requests.get(0)).contains("client_id=client-id", "refresh_token=refresh-for-secret%2Fref", "grant_type=refresh_token");
        assertThat(requests.get(1)).startsWith("send Bearer at-123 ");
        String raw = requests.get(1).replaceAll("(?s).*\"raw\":\"([^\"]+)\".*", "$1");
        String mime = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        assertThat(mime).contains("To: hr@example.com", "From: me@example.com", "multipart/mixed", "application/pdf", "filename=CV.pdf");
        // The non-ASCII subject is encoded, not sent as raw bytes.
        assertThat(mime).contains("Subject: =?UTF-8?");
    }

    @Test
    void aRevokedRefreshTokenMeansGmailIsNotConnected() {
        tokenStatus = body -> new int[] {400};

        assertThatThrownBy(() -> sender("client-id").send("ref", email())).isInstanceOf(GmailNotConnectedException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void aRefusedSendMeansGmailIsNotConnected() {
        sendStatus = 403;

        assertThatThrownBy(() -> sender("client-id").send("ref", email())).isInstanceOf(GmailNotConnectedException.class);
    }

    @Test
    void aServerErrorIsAFailureNotADisconnect() {
        sendStatus = 500;

        assertThatThrownBy(() -> sender("client-id").send("ref", email()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(GmailNotConnectedException.class);
    }

    @Test
    void withoutAnOAuthClientNothingIsCalled() {
        assertThatThrownBy(() -> sender("").send("ref", email())).isInstanceOf(GmailNotConnectedException.class);
        assertThat(requests).isEmpty();
    }
}
