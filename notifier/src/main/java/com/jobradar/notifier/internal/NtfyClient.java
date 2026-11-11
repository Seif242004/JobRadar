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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Free push channel through ntfy.sh, used instead of Telegram when {@code jobradar.ntfy.topic} is
 * set. The topic name is the secret: use a long random one.
 *
 * <p>Messages are published to the topic. A button that must report back is an ntfy "http" action:
 * the phone app POSTs the callback data to the companion topic {@code <topic>-taps}, and
 * {@link #getUpdates} reads that topic, so the api still needs no public endpoint. ntfy shows at
 * most three buttons per notification, so a fourth is dropped (the lowest-priority one is last).
 * ntfy cannot edit a sent message, so {@link #clearButtons} does nothing; repeated taps are
 * harmless because every action is idempotent.
 */
@Component
@ConditionalOnExpression("!'${jobradar.ntfy.topic:}'.isEmpty()")
class NtfyClient implements TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(NtfyClient.class);
    private static final int MAX_ACTIONS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String baseUrl;
    private final String topic;
    private final long chatId;

    NtfyClient(
            JsonMapper json,
            @Value("${jobradar.ntfy.base-url:https://ntfy.sh}") String baseUrl,
            @Value("${jobradar.ntfy.topic}") String topic,
            @Value("${jobradar.ntfy.chat-id:1}") long chatId) {
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.topic = topic;
        this.chatId = chatId;
    }

    @Override
    public void sendMessage(long ignoredChatId, String text, String buttonLabel, String buttonUrl) {
        sendWithButtons(ignoredChatId, text, buttonUrl == null ? List.of() : List.of(List.of(Button.link(buttonLabel, buttonUrl))));
    }

    @Override
    public void sendWithButtons(long ignoredChatId, String text, List<List<Button>> rows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        String[] lines = text.split("\n", 2);
        body.put("title", lines[0].length() > 120 ? lines[0].substring(0, 120) : lines[0]);
        body.put("message", lines.length > 1 && !lines[1].isBlank() ? lines[1] : lines[0]);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (List<Button> row : rows) {
            for (Button button : row) {
                if (actions.size() == MAX_ACTIONS) {
                    break;
                }
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("label", button.label());
                if (button.url() != null) {
                    action.put("action", "view");
                    action.put("url", button.url());
                } else {
                    action.put("action", "http");
                    action.put("url", baseUrl + "/" + topic + "-taps");
                    action.put("method", "POST");
                    action.put("body", button.callbackData());
                    action.put("clear", true);
                }
                actions.add(action);
            }
        }
        if (!actions.isEmpty()) {
            body.put("actions", actions);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("ntfy answered HTTP {}", response.statusCode());
            }
        } catch (IOException e) {
            log.warn("Could not publish to ntfy: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads the taps topic. {@code offset} is the unix time to read from; returns immediately, so it waits a little when idle. */
    @Override
    public Updates getUpdates(long offset, int timeoutSeconds) {
        long since = offset > 0 ? offset : System.currentTimeMillis() / 1000;
        List<Tap> taps = new ArrayList<>();
        long next = since;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + topic + "-taps/json?poll=1&since=" + since))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            for (String line : response.body().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = json.readTree(line);
                if (!"message".equals(event.path("event").asText())) {
                    continue;
                }
                long time = event.path("time").asLong();
                taps.add(new Tap(time, event.path("id").asText(), chatId, chatId, 0, event.path("message").asText()));
                next = Math.max(next, time + 1);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read ntfy taps: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (taps.isEmpty()) {
            try {
                Thread.sleep(Math.min(timeoutSeconds, 4) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new Updates(next, taps);
    }

    @Override
    public void answerTap(String callbackId, String text) {
        // ntfy has no per-tap acknowledgement.
    }

    @Override
    public void clearButtons(long ignoredChatId, long messageId) {
        // ntfy cannot edit a sent notification.
    }
}
