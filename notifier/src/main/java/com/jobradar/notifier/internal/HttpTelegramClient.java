package com.jobradar.notifier.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telegram Bot API client. Telegram is an official API that the user opted into by starting the
 * bot; nothing here touches a job site. Button taps are fetched by long polling, so the api needs
 * no public endpoint.
 */
@Component
class HttpTelegramClient implements TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTelegramClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String botToken;
    private final String apiUrl;

    HttpTelegramClient(
            JsonMapper json,
            @Value("${jobradar.telegram.bot-token:}") String botToken,
            @Value("${jobradar.telegram.api-url:https://api.telegram.org}") String apiUrl) {
        this.json = json;
        this.botToken = botToken;
        this.apiUrl = apiUrl;
    }

    @Override
    public void sendMessage(long chatId, String text, String buttonLabel, String buttonUrl) {
        sendWithButtons(chatId, text, buttonUrl == null ? List.of() : List.of(List.of(Button.link(buttonLabel, buttonUrl))));
    }

    @Override
    public void sendWithButtons(long chatId, String text, List<List<Button>> rows) {
        if (botToken.isBlank()) {
            // No token configured (local development): the alert is visible in the log instead.
            log.info("Telegram disabled, alert not sent: {}", text.lines().findFirst().orElse(""));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        if (!rows.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", rows.stream().map(HttpTelegramClient::row).toList()));
        }
        call("sendMessage", body, Duration.ofSeconds(10));
    }

    @Override
    public Updates getUpdates(long offset, int timeoutSeconds) {
        if (botToken.isBlank()) {
            return new Updates(offset, List.of());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", timeoutSeconds);
        body.put("allowed_updates", List.of("callback_query"));
        JsonNode result = call("getUpdates", body, Duration.ofSeconds(timeoutSeconds + 10L)).path("result");
        long next = offset;
        List<Tap> taps = new ArrayList<>();
        for (JsonNode update : result) {
            long updateId = update.path("update_id").asLong();
            next = Math.max(next, updateId + 1);
            JsonNode query = update.path("callback_query");
            if (query.isMissingNode() || !query.path("message").has("message_id")) {
                continue;
            }
            taps.add(new Tap(
                    updateId,
                    query.path("id").asString(""),
                    query.path("from").path("id").asLong(),
                    query.path("message").path("chat").path("id").asLong(),
                    query.path("message").path("message_id").asLong(),
                    query.path("data").asString("")));
        }
        return new Updates(next, taps);
    }

    @Override
    public void answerTap(String callbackId, String text) {
        if (!botToken.isBlank()) {
            call("answerCallbackQuery", Map.<String, Object>of("callback_query_id", callbackId, "text", text), Duration.ofSeconds(10));
        }
    }

    @Override
    public void clearButtons(long chatId, long messageId) {
        if (!botToken.isBlank()) {
            call(
                    "editMessageReplyMarkup",
                    Map.<String, Object>of(
                            "chat_id", chatId, "message_id", messageId, "reply_markup", Map.of("inline_keyboard", List.of())),
                    Duration.ofSeconds(10));
        }
    }

    private static List<Map<String, String>> row(List<Button> buttons) {
        return buttons.stream()
                .map(button -> button.url() != null
                        ? Map.of("text", button.label(), "url", button.url())
                        : Map.of("text", button.label(), "callback_data", button.callbackData()))
                .toList();
    }

    private JsonNode call(String method, Map<String, Object> body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(
                        // The token is part of the URL, so this URI must never be logged.
                        URI.create(apiUrl + "/bot" + botToken + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Telegram " + method + " answered " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Telegram is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Telegram", e);
        }
    }
}
