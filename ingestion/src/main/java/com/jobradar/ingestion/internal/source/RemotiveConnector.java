package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.SourceSchedule;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Remotive's public remote-jobs API. {@code GET https://remotive.com/api/remote-jobs}
 *
 * <p>Remotive's terms, as stated in every response: at most 4 requests a day, link back to the
 * Remotive URL and name Remotive as the source, never republish to other job sites. So the
 * schedule is one run every 6 hours, {@code apply_target} is the Remotive URL itself and
 * {@code source} is {@code remotive}. Jobs are delayed 24 hours by Remotive, so they never land
 * in the hot queue.
 */
@Component
class RemotiveConnector implements SourceConnector {

    static final String SOURCE = "remotive";
    static final SourceSchedule SCHEDULE = new SourceSchedule(Duration.ofHours(6), 4);
    private static final URI FEED = URI.create("https://remotive.com/api/remote-jobs");

    private final JsonFetch json;

    RemotiveConnector(JsonFetch json) {
        this.json = json;
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
        for (JsonNode job : json.get(SOURCE, FEED).path("jobs")) {
            String id = SourceText.text(job, "id");
            String title = SourceText.text(job, "title");
            if (id == null || title == null) {
                continue;
            }
            postings.add(new RawPosting(
                    SOURCE,
                    id,
                    SourceText.text(job, "company_name"),
                    title,
                    // Where the candidate must be, for example "Worldwide" or "USA".
                    SourceText.text(job, "candidate_required_location"),
                    "remote",
                    null,
                    SourceText.plain(SourceText.text(job, "description")),
                    // Applying happens on Remotive's own page (link-back rule).
                    "platform",
                    SourceText.text(job, "url"),
                    null,
                    // No offset in the feed; read as UTC.
                    SourceText.instant(SourceText.text(job, "publication_date"))));
        }
        return postings;
    }
}
