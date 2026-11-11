package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Ashby public job board API: no authentication for listed postings.
 * {@code GET https://api.ashbyhq.com/posting-api/job-board/{token}}.
 */
@Component
class AshbyConnector extends BoardConnector {

    static final String SOURCE = "ashby";
    private static final String BASE_URL = "https://api.ashbyhq.com/posting-api/job-board/";

    private final JsonFetch json;

    AshbyConnector(BoardDirectory boards, JsonFetch json) {
        super(boards);
        this.json = json;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    List<RawPosting> fetchBoard(Board board) {
        URI uri = URI.create(BASE_URL + URLEncoder.encode(board.token(), StandardCharsets.UTF_8));
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : json.get(SOURCE, uri).path("jobs")) {
            String id = SourceText.text(job, "id");
            String title = SourceText.text(job, "title");
            if (id == null || title == null || job.path("isListed").asBoolean(true) == false) {
                continue;
            }
            String applyUrl = SourceText.text(job, "applyUrl");
            postings.add(new RawPosting(
                    SOURCE,
                    id,
                    board.companyName(),
                    title,
                    SourceText.text(job, "location"),
                    remotePolicy(SourceText.text(job, "workplaceType")),
                    null,
                    SourceText.text(job, "descriptionPlain"),
                    "ats_form",
                    applyUrl != null ? applyUrl : SourceText.text(job, "jobUrl"),
                    null,
                    publishedAt(job)));
        }
        return postings;
    }

    /** Ashby values: Remote, Hybrid, OnSite. */
    private static String remotePolicy(String workplaceType) {
        if (workplaceType == null) {
            return null;
        }
        return switch (workplaceType.toLowerCase(java.util.Locale.ROOT)) {
            case "remote" -> "remote";
            case "hybrid" -> "hybrid";
            case "onsite" -> "onsite";
            default -> null;
        };
    }

    private static Instant publishedAt(JsonNode job) {
        String value = SourceText.text(job, "publishedAt");
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
