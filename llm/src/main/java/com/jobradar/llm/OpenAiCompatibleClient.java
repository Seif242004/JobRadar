package com.jobradar.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat completions and embeddings over any OpenAI-compatible HTTP API (NVIDIA Build by default).
 *
 * <p>Plain HTTP on purpose: no provider SDK, so retries, the budget cap and model fallback stay
 * under our control and a provider swap is a configuration change.
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class OpenAiCompatibleClient implements LlmClient, EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AiProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    // Budget counter. Guarded by "this"; resets when the UTC date changes.
    private LocalDate countedDay;
    private int callsToday;

    public OpenAiCompatibleClient(AiProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String complete(String system, String user) {
        requireKey();
        reserveCall();

        // Providers retire model IDs; a 404 means "try the next one", anything else is final.
        Set<String> models = new LinkedHashSet<>();
        models.add(properties.model());
        models.addAll(properties.fallbackModels());
        LlmUnavailableException last = null;
        for (String model : models) {
            HttpResponse<String> response = post("/chat/completions", Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "max_tokens", 2000,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user))));
            if (response.statusCode() == 404) {
                last = new LlmUnavailableException("Model " + model + " is not available");
                continue;
            }
            JsonNode content = parse(response).path("choices").path(0).path("message").path("content");
            if (!content.isString() || content.asString().isBlank()) {
                throw new LlmUnavailableException("The model returned no text");
            }
            return content.asString();
        }
        throw last;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        requireKey();
        if (texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", properties.embeddingModel());
        body.put("input", texts);
        body.put("encoding_format", "float");
        if (properties.embeddingDimensions() > 0) {
            body.put("dimensions", properties.embeddingDimensions());
        }
        if (!properties.embeddingInputType().isBlank()) {
            body.put("input_type", properties.embeddingInputType());
        }
        JsonNode data = parse(post("/embeddings", body)).path("data");
        if (data.size() != texts.size()) {
            throw new LlmUnavailableException("Expected " + texts.size() + " embeddings, got " + data.size());
        }
        // The API may return items out of order; "index" says which input each belongs to.
        float[][] vectors = new float[texts.size()][];
        for (JsonNode item : data) {
            JsonNode values = item.path("embedding");
            boolean truncate = properties.embeddingTruncate() && values.size() > DIMENSIONS;
            if (values.size() != DIMENSIONS && !truncate) {
                throw new LlmUnavailableException("Embedding model returned " + values.size()
                        + " dimensions, the schema expects " + DIMENSIONS);
            }
            float[] vector = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            if (truncate) {
                double norm = 0;
                for (float v : vector) {
                    norm += (double) v * v;
                }
                norm = Math.sqrt(norm);
                for (int i = 0; i < DIMENSIONS && norm > 0; i++) {
                    vector[i] = (float) (vector[i] / norm);
                }
            }
            vectors[item.path("index").asInt()] = vector;
        }
        return new ArrayList<>(List.of(vectors));
    }

    /** Calls made today, for the admin view and tests. */
    public synchronized int callsToday() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate().equals(countedDay) ? callsToday : 0;
    }

    private void requireKey() {
        if (properties.key().isBlank()) {
            throw new LlmUnavailableException("No API key configured (JOBRADAR_AI_KEY)");
        }
    }

    /** Counts the call against the daily cap before it is made, so the cap can never be exceeded. */
    private synchronized void reserveCall() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        if (!today.equals(countedDay)) {
            countedDay = today;
            callsToday = 0;
        }
        if (callsToday >= properties.dailyCallCap()) {
            throw new LlmUnavailableException("Daily LLM call cap of " + properties.dailyCallCap() + " reached");
        }
        callsToday++;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + path))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.key())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmUnavailableException("AI provider is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while calling the AI provider", e);
        }
    }

    private JsonNode parse(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            // The body can echo the prompt, so only the status is logged and reported.
            log.warn("AI provider answered {}", response.statusCode());
            throw new LlmUnavailableException("AI provider answered " + response.statusCode());
        }
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("AI provider returned malformed JSON", e);
        }
    }
}
