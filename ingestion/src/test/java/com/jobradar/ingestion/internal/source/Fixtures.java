package com.jobradar.ingestion.internal.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Serves the recorded responses in {@code src/test/resources/fixtures} instead of the network. */
public final class Fixtures {

    private Fixtures() {}

    /** The content of {@code fixtures/<path>}. */
    public static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonFetch jsonFetch(HttpFetcher http) {
        return new JsonFetch(http, JsonMapper.builder().build());
    }

    /** An {@link HttpFetcher} that answers exact URLs with canned responses and records every call. */
    public static final class FakeHttp implements HttpFetcher {

        private final Map<String, FetchResponse> responses = new LinkedHashMap<>();
        public final List<String> requested = new ArrayList<>();

        public FakeHttp on(String url, FetchResponse response) {
            responses.put(url, response);
            return this;
        }

        /** Answers {@code url} with HTTP 200 and the fixture at {@code fixturePath}. */
        public FakeHttp fixture(String url, String fixturePath) {
            return on(url, FetchResponse.ok(read(fixturePath)));
        }

        @Override
        public FetchResponse get(URI uri) {
            requested.add(uri.toString());
            FetchResponse response = responses.get(uri.toString());
            if (response == null) {
                // A test that reaches for an unplanned URL fails here instead of going online.
                throw new SourceFetchException("No fixture for " + uri);
            }
            return response;
        }
    }
}
