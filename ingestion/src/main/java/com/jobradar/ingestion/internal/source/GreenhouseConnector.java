package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Greenhouse Job Board API: public, no authentication for reading jobs.
 * {@code GET https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true}
 */
@Component
class GreenhouseConnector extends BoardConnector {

    static final String SOURCE = "greenhouse";
    private static final String BASE_URL = "https://boards-api.greenhouse.io/v1/boards/";

    private final JsonFetch json;

    GreenhouseConnector(BoardDirectory boards, JsonFetch json) {
        super(boards);
        this.json = json;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    List<RawPosting> fetchBoard(Board board) {
        URI uri = URI.create(BASE_URL + URLEncoder.encode(board.token(), StandardCharsets.UTF_8) + "/jobs?content=true");
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : json.get(SOURCE, uri).path("jobs")) {
            String id = SourceText.text(job, "id");
            String title = SourceText.text(job, "title");
            if (id == null || title == null) {
                continue; // posting.source_id and posting.title are NOT NULL
            }
            String location = SourceText.text(job.path("location"), "name");
            postings.add(new RawPosting(
                    SOURCE,
                    // Job IDs are unique per Greenhouse instance, not globally: prefix the board.
                    board.token() + ":" + id,
                    board.companyName(),
                    title,
                    location,
                    remotePolicy(location),
                    SourceText.text(job, "language"),
                    // "content" is HTML with its markup entity-escaped once more.
                    SourceText.plain(SourceText.unescape(SourceText.text(job, "content"))),
                    "ats_form",
                    SourceText.text(job, "absolute_url"),
                    SourceText.instant(SourceText.text(job, "application_deadline")),
                    SourceText.instant(SourceText.text(job, "first_published"))));
        }
        return postings;
    }

    /** Greenhouse has no workplace field; only an explicit "Remote" in the location is trusted. */
    private static String remotePolicy(String location) {
        return location != null && location.toLowerCase(Locale.ROOT).contains("remote") ? "remote" : null;
    }
}
