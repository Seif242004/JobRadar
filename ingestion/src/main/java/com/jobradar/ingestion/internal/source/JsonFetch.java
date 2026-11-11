package com.jobradar.ingestion.internal.source;

import java.net.URI;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET + JSON parse with the error handling every connector needs. */
@Component
public class JsonFetch {

    private final HttpFetcher http;
    private final JsonMapper json;

    public JsonFetch(HttpFetcher http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /**
     * @throws SourceRateLimitedException on HTTP 429
     * @throws SourceFetchException       on any other non-2xx status or a body that is not JSON
     */
    public JsonNode get(String source, URI uri) {
        FetchResponse response = http.get(uri);
        if (response.status() == 429) {
            throw new SourceRateLimitedException(source, response.retryAfter());
        }
        if (!response.isSuccess()) {
            throw new SourceFetchException(source + " answered HTTP " + response.status() + " for " + uri.getPath());
        }
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new SourceFetchException(source + " sent a body that is not JSON for " + uri.getPath(), e);
        }
    }
}
