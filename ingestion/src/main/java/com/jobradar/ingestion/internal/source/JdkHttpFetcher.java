package com.jobradar.ingestion.internal.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** {@link HttpFetcher} on the JDK's HTTP client: no extra dependency, honours the JVM proxy settings. */
@Component
class JdkHttpFetcher implements HttpFetcher {

    /** Identifies the client honestly to every source; carries no personal data. */
    static final String USER_AGENT = "JobRadar/0.1 (personal job-search tool)";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public FetchResponse get(URI uri) {
        return get(uri, java.util.Map.of());
    }

    @Override
    public FetchResponse get(URI uri, java.util.Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.5");
        headers.forEach(builder::header);
        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new FetchResponse(response.statusCode(), response.body(), retryAfter(response));
        } catch (IOException e) {
            throw new SourceFetchException("GET " + uri.getHost() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceFetchException("GET " + uri.getHost() + " was interrupted", e);
        }
    }

    /** Only the seconds form is read; the HTTP-date form falls back to the default back-off. */
    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .filter(value -> value.matches("\\d{1,9}"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(null);
    }
}
