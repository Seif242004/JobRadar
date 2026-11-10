package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Workable's public cross-employer job search, filtered by location.
 * {@code GET https://jobs.workable.com/api/v1/jobs?location=Egypt}
 *
 * <p>One request per configured location and run, first page only (the newest postings). The
 * endpoint rate-limits per IP and answers 429 with a long {@code Retry-After}; that surfaces as a
 * {@link SourceRateLimitedException} and the scheduler waits it out.
 */
@Component
class WorkableSearchConnector implements SourceConnector {

    static final String SOURCE = "workable";
    private static final String SEARCH_URL = "https://jobs.workable.com/api/v1/jobs?location=";

    private final JsonFetch json;
    private final List<String> locations;

    WorkableSearchConnector(JsonFetch json, @Value("${jobradar.ingestion.workable.locations:Egypt}") List<String> locations) {
        this.json = json;
        this.locations = locations;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<RawPosting> fetch() {
        List<RawPosting> postings = new ArrayList<>();
        for (String location : locations) {
            URI uri = URI.create(SEARCH_URL + URLEncoder.encode(location, StandardCharsets.UTF_8));
            for (JsonNode job : json.get(SOURCE, uri).path("jobs")) {
                RawPosting posting = map(job);
                if (posting != null) {
                    postings.add(posting);
                }
            }
        }
        return postings;
    }

    private static RawPosting map(JsonNode job) {
        String id = SourceText.text(job, "id");
        String title = SourceText.text(job, "title");
        if (id == null || title == null || !"published".equals(SourceText.text(job, "state"))) {
            return null;
        }
        JsonNode location = job.path("location");
        return new RawPosting(
                SOURCE,
                id,
                SourceText.text(job.path("company"), "title"),
                title,
                // "Cairo, Egypt": the form the other sources use, so content hashes line up.
                SourceText.join(", ", SourceText.text(location, "city"), SourceText.text(location, "countryName")),
                remotePolicy(SourceText.text(job, "workplace")),
                SourceText.text(job, "language"),
                SourceText.join(
                        "\n\n",
                        SourceText.plain(SourceText.text(job, "description")),
                        SourceText.plain(SourceText.text(job, "requirementsSection")),
                        SourceText.plain(SourceText.text(job, "benefitsSection"))),
                "ats_form",
                SourceText.text(job, "url"),
                null,
                SourceText.instant(SourceText.text(job, "created")));
    }

    /** Workable values: remote, hybrid, on_site. */
    private static String remotePolicy(String workplace) {
        if (workplace == null) {
            return null;
        }
        return workplace.equals("on_site") ? "onsite" : workplace;
    }
}
