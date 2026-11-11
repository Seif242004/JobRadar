package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lever Postings API: public, no authentication for published postings.
 * {@code GET https://api.lever.co/v0/postings/{token}?mode=json}. Board tokens are case-sensitive.
 */
@Component
class LeverConnector extends BoardConnector {

    static final String SOURCE = "lever";
    private static final String BASE_URL = "https://api.lever.co/v0/postings/";

    private final JsonFetch json;

    LeverConnector(BoardDirectory boards, JsonFetch json) {
        super(boards);
        this.json = json;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    List<RawPosting> fetchBoard(Board board) {
        URI uri = URI.create(BASE_URL + URLEncoder.encode(board.token(), StandardCharsets.UTF_8) + "?mode=json");
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : json.get(SOURCE, uri)) {
            String id = SourceText.text(job, "id");
            String title = SourceText.text(job, "text");
            if (id == null || title == null) {
                continue;
            }
            String applyUrl = SourceText.text(job, "applyUrl");
            postings.add(new RawPosting(
                    SOURCE,
                    id, // a UUID, unique across boards
                    board.companyName(),
                    title,
                    SourceText.text(job.path("categories"), "location"),
                    remotePolicy(SourceText.text(job, "workplaceType")),
                    null, // Lever does not state the posting language
                    description(job),
                    "ats_form",
                    applyUrl != null ? applyUrl : SourceText.text(job, "hostedUrl"),
                    null,
                    createdAt(job)));
        }
        return postings;
    }

    /** Lever values: remote, hybrid, onsite, unspecified. */
    private static String remotePolicy(String workplaceType) {
        return workplaceType == null || workplaceType.equals("unspecified") ? null : workplaceType;
    }

    /** {@code createdAt} is epoch milliseconds. */
    private static Instant createdAt(JsonNode job) {
        JsonNode createdAt = job.path("createdAt");
        return createdAt.isNumber() ? Instant.ofEpochMilli(createdAt.asLong()) : null;
    }

    /** Intro, then each list (responsibilities, requirements) under its heading, then the closing text. */
    private static String description(JsonNode job) {
        StringBuilder lists = new StringBuilder();
        for (JsonNode list : job.path("lists")) {
            String section = SourceText.join(
                    "\n", SourceText.text(list, "text"), SourceText.plain(SourceText.text(list, "content")));
            if (section != null) {
                lists.append(lists.isEmpty() ? "" : "\n\n").append(section);
            }
        }
        return SourceText.join(
                "\n\n", SourceText.text(job, "descriptionPlain"), lists.toString(), SourceText.text(job, "additionalPlain"));
    }
}
