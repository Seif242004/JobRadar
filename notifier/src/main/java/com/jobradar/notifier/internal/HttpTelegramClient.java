package com.jobradar.notifier.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telegram Bot API client ({@code sendMessage} only). Telegram is an official API that the user
 * opted into by starting the bot; nothing here touches a job site.
 */
@Component
class HttpTelegramClient implements TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTelegramClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String botToken;

    HttpTelegramClient(JsonMapper json, @Value("${jobradar.telegram.bot-token:}") String botToken) {
        this.json = json;
        this.botToken = botToken;
    }

    @Override
    public void sendMessage(long chatId, String text, String buttonLabel, String buttonUrl) {
        if (botToken.isBlank()) {
            // No token configured (local development): the alert is visible in the log instead.
            log.info("Telegram disabled, alert not sent: {}", text.lines().findFirst().orElse(""));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        if (buttonUrl != null) {
            body.put("reply_markup", Map.of(
                    "inline_keyboard", List.of(List.of(Map.of("text", buttonLabel, "url", buttonUrl)))));
        }
        HttpRequest request = HttpRequest.newBuilder(
                        // The token is part of the URL, so this URI must never be logged.
                        URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Telegram answered " + response.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Telegram is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Telegram", e);
        }
    }
}
