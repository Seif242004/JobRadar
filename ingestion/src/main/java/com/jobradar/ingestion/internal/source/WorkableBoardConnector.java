package com.jobradar.ingestion.internal.source;

import com.jobradar.ingestion.RawPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Workable company widget: the public job list every Workable careers page loads.
 * {@code GET https://apply.workable.com/api/v1/widget/accounts/{token}?details=true}
 *
 * <p>Response shape as documented by public integrations (not by Workable itself):
 * {@code {name, description, jobs: [{title, shortcode, shortlink, url, department, city, state,
 * country, telecommuting, published_on, description}]}}. One request returns the whole board.
 * A posting that is open in several countries comes back once per location with the same
 * {@code shortcode}; those entries are folded into one posting here.
 *
 * <p>Cloudflare fronts every Workable tenant on one host and answers HTTP 429 with a
 * {@code Retry-After} of hours when it limits an IP. That surfaces as a
 * {@link SourceRateLimitedException}; the run stops and the scheduler waits it out. The host is
 * not retried and the limit is not worked around.
 */
@Component
class WorkableBoardConnector extends BoardConnector {

    static final String SOURCE = "workable-board";
    private static final String ATS_TYPE = "workable";
    private static final String BASE_URL = "https://apply.workable.com/api/v1/widget/accounts/";
    private static final String LOCATION_SEPARATOR = "; ";

    private final JsonFetch json;
    private final Clock clock;

    WorkableBoardConnector(BoardDirectory boards, JsonFetch json, Clock clock) {
        super(boards);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    String atsType() {
        return ATS_TYPE;
    }

    /** One accumulating posting while its fanned-out location entries are folded. */
    private static final class Folded {
        final JsonNode first;
        final Set<String> locations = new LinkedHashSet<>();
        boolean telecommuting;

        Folded(JsonNode first) {
            this.first = first;
        }
    }

    @Override
    List<RawPosting> fetchBoard(Board board) {
        URI uri = URI.create(BASE_URL + URLEncoder.encode(board.token(), StandardCharsets.UTF_8) + "?details=true");
        JsonNode jobs = json.get(SOURCE, uri).path("jobs");

        Map<String, Folded> byShortcode = new LinkedHashMap<>();
        for (JsonNode job : jobs) {
            String shortcode = SourceText.text(job, "shortcode");
            String title = SourceText.text(job, "title");
            if (shortcode == null || title == null) {
                continue; // posting.source_id and posting.title are NOT NULL
            }
            Folded folded = byShortcode.computeIfAbsent(shortcode, key -> new Folded(job));
            String location = location(job);
            if (location != null) {
                folded.locations.add(location);
            }
            folded.telecommuting |= job.path("telecommuting").asBoolean(false);
        }

        List<RawPosting> postings = new ArrayList<>();
        for (Map.Entry<String, Folded> entry : byShortcode.entrySet()) {
            Folded folded = entry.getValue();
            JsonNode job = folded.first;
            String url = SourceText.text(job, "shortlink");
            postings.add(new RawPosting(
                    SOURCE,
                    // Shortcodes are unique inside one account only.
                    board.token() + ":" + entry.getKey(),
                    board.companyName(),
                    SourceText.text(job, "title"),
                    folded.locations.isEmpty() ? null : String.join(LOCATION_SEPARATOR, folded.locations),
                    folded.telecommuting ? "remote" : null,
                    null,
                    SourceText.plain(SourceText.text(job, "description")),
                    "ats_form",
                    url != null ? url : SourceText.text(job, "url"),
                    null,
                    postedAt(SourceText.text(job, "published_on"))));
        }
        return postings;
    }

    /** "City, Country", the form the other sources use so content hashes line up; either part may be missing. */
    private static String location(JsonNode job) {
        return SourceText.join(", ", SourceText.text(job, "city"), SourceText.text(job, "country"));
    }

    /**
     * {@code published_on} is a calendar date, not a time. A date before today is a safe upper
     * bound on the age (start of that day). A posting dated today could be minutes or hours old,
     * and start of day would wrongly push it out of the hot band, so it counts as "no posted_at":
     * fresh on its first sighting, exactly the rule SPEC.md section 3 gives for such sources.
     */
    private Instant postedAt(String publishedOn) {
        Instant parsed = SourceText.instant(publishedOn);
        if (parsed == null) {
            return null;
        }
        LocalDate today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
        return parsed.atOffset(ZoneOffset.UTC).toLocalDate().isBefore(today) ? parsed : null;
    }
}
