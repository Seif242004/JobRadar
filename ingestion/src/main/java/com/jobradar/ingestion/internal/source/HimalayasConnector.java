package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.SourceSchedule;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Himalayas' official jobs API, filtered to roles open to Egypt (including worldwide ones).
 * {@code GET https://himalayas.app/jobs/api/search?country=Egypt&page=N}, with the free API key
 * as a bearer token.
 *
 * <p>Its terms: jobs may be shown on your own site or app, never forwarded to other job boards,
 * aggregators or Google Jobs; show a visible source link; use the application link unchanged. So
 * {@code apply_target} is the unchanged {@code applicationLink}, the description ends with a
 * "Source: Himalayas" line and the job's Himalayas URL, and nothing here leaves JobRadar. The limit
 * is 60 requests a minute and 10,000 a day; this uses at most {@value #PAGES} requests per run.
 * Without a key the connector does nothing.
 */
@Component
class HimalayasConnector implements SourceConnector {

    static final String SOURCE = "himalayas";
    static final SourceSchedule SCHEDULE = SourceSchedule.ATS;
    static final int PAGES = 3;
    private static final String BASE = "https://himalayas.app/jobs/api/search?country=Egypt&page=";

    private final JsonFetch json;
    private final String apiKey;

    HimalayasConnector(JsonFetch json, @Value("${jobradar.ingestion.himalayas.api-key:}") String apiKey) {
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public SourceSchedule schedule() {
        return SCHEDULE;
    }

    @Override
    public List<RawPosting> fetch() {
        List<RawPosting> postings = new ArrayList<>();
        if (apiKey.isEmpty()) {
            return postings;
        }
        Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
        for (int page = 1; page <= PAGES; page++) {
            JsonNode jobs = json.get(SOURCE, URI.create(BASE + page), headers).path("jobs");
            for (JsonNode job : jobs) {
                RawPosting posting = map(job);
                if (posting != null) {
                    postings.add(posting);
                }
            }
            if (jobs.size() < 20) {
                break;
            }
        }
        return postings;
    }

    private static RawPosting map(JsonNode job) {
        String guid = SourceText.text(job, "guid");
        String title = SourceText.text(job, "title");
        String link = SourceText.text(job, "applicationLink");
        if (guid == null || title == null) {
            return null;
        }
        List<String> places = new ArrayList<>();
        job.path("locationRestrictions").forEach(place -> places.add(place.asString()));
        String description = SourceText.plain(SourceText.text(job, "description"));
        String employment = SourceText.text(job, "employmentType");
        String text = (employment == null ? "" : "Employment type: " + employment + "\n")
                + (description == null ? "" : description + "\n")
                + "Source: Himalayas, " + guid;
        long published = job.path("pubDate").asLong(0);
        long expiry = job.path("expiryDate").asLong(0);
        return new RawPosting(
                SOURCE,
                guid,
                SourceText.text(job, "companyName"),
                title,
                // Empty restriction list means open worldwide.
                places.isEmpty() ? "Worldwide" : String.join(", ", places),
                "remote",
                null,
                text,
                "platform",
                link != null ? link : guid,
                expiry > 0 ? Instant.ofEpochSecond(expiry) : null,
                published > 0 ? Instant.ofEpochSecond(published) : null);
    }

    /** For tests: the URL of a result page. */
    static String pageUrl(int page) {
        return BASE + URLEncoder.encode(Integer.toString(page), StandardCharsets.UTF_8);
    }
}
