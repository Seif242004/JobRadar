package com.jobradar.ingestion.internal.alerts;

import com.jobradar.ingestion.internal.source.SourceRateLimitedException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gmail API over plain HTTP, the same way the apply module sends mail. The consent granted by
 * {@code scripts/gmail_authorize.py} includes {@code gmail.readonly}: Google has no narrower scope
 * that returns message bodies, and none that is limited to one label. The one-label limit is
 * therefore enforced here: the only calls made are the token exchange, {@code labels.list},
 * {@code messages.list} with a {@code labelIds} filter, and {@code messages.get}. Nothing is
 * ever modified, and tokens and message content are never logged.
 */
@Component
class HttpGmailApi implements GmailApi {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final GmailAlertProperties properties;
    private final JsonMapper json;

    HttpGmailApi(GmailAlertProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public String accessToken(String refreshToken) {
        String form = "client_id=" + enc(properties.clientId())
                + "&client_secret=" + enc(properties.clientSecret())
                + "&refresh_token=" + enc(refreshToken)
                + "&grant_type=refresh_token";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(properties.tokenUrl()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            // invalid_grant: the user revoked access or the token expired.
            throw new GmailAccessException("Google rejected the refresh token");
        }
        failOnError(response);
        String token = parse(response).path("access_token").asString("");
        if (token.isBlank()) {
            throw new IllegalStateException("Google returned no access token");
        }
        return token;
    }

    @Override
    public Optional<String> labelId(String accessToken, String labelName) {
        JsonNode labels = get(accessToken, "/users/me/labels").path("labels");
        for (JsonNode label : labels) {
            if (labelName.equalsIgnoreCase(label.path("name").asString(""))) {
                String id = label.path("id").asString("");
                return id.isBlank() ? Optional.empty() : Optional.of(id);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> messageIds(String accessToken, String labelId, int newerThanDays, int max) {
        String query = "?labelIds=" + enc(labelId) + "&q=" + enc("newer_than:" + newerThanDays + "d") + "&maxResults=" + max;
        List<String> ids = new ArrayList<>();
        for (JsonNode message : get(accessToken, "/users/me/messages" + query).path("messages")) {
            String id = message.path("id").asString("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public RawMessage message(String accessToken, String messageId) {
        JsonNode message = get(accessToken, "/users/me/messages/" + enc(messageId) + "?format=raw");
        String raw = message.path("raw").asString("");
        if (raw.isBlank()) {
            throw new IllegalStateException("Gmail returned no content for a message");
        }
        long internalDate = message.path("internalDate").asString("").isBlank() ? 0 : Long.parseLong(message.path("internalDate").asString());
        return new RawMessage(
                Base64.getUrlDecoder().decode(raw), internalDate > 0 ? Instant.ofEpochMilli(internalDate) : Instant.now());
    }

    private JsonNode get(String accessToken, String path) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(properties.apiUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new GmailAccessException("Gmail refused the request");
        }
        failOnError(response);
        return parse(response);
    }

    private void failOnError(HttpResponse<String> response) {
        if (response.statusCode() == 429) {
            Duration retryAfter = response.headers().firstValue("Retry-After")
                    .filter(value -> value.matches("\\d{1,9}"))
                    .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                    .orElse(null);
            throw new SourceRateLimitedException("gmail", retryAfter);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Google answered HTTP " + response.statusCode());
        }
    }

    private JsonNode parse(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new IllegalStateException("Google sent a body that is not JSON");
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Google is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Google", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
