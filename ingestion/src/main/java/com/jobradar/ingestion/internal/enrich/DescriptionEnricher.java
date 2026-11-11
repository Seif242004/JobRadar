package com.jobradar.ingestion.internal.enrich;

import com.jobradar.ingestion.PostingIngested;
import com.jobradar.ingestion.PostingPublisher;
import com.jobradar.ingestion.PostingQueue;
import com.jobradar.ingestion.internal.PostingAge;
import com.jobradar.ingestion.internal.resolver.RobotsRules;
import com.jobradar.ingestion.internal.source.FetchResponse;
import com.jobradar.ingestion.internal.source.HttpFetcher;
import com.jobradar.ingestion.internal.source.SourceFetchException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Replaces a thin description (an e-mail alert snippet) with the full text of the public apply page.
 *
 * <p>Allowed: public pages that robots.txt permits, one request per posting, a handful per run.
 * Never fetched: LinkedIn, Wuzzuf and other job boards that forbid automated access. Those
 * postings keep their snippet and are flagged as low confidence on the dashboard. After a
 * successful fetch the stored embedding is dropped and the posting is queued again, so matching
 * re-scores it from the full text.
 */
public class DescriptionEnricher {

    private static final Logger log = LoggerFactory.getLogger(DescriptionEnricher.class);

    /** A description shorter than this is treated as a snippet. */
    static final int THIN_BELOW = 600;
    private static final int BATCH = 15;
    private static final String AGENT = "JobRadar";
    /** Job boards whose pages must not be fetched by a program. */
    private static final List<String> BLOCKED_HOSTS = List.of(
            "linkedin.com", "wuzzuf.net", "bayt.com", "indeed.com", "glassdoor.com", "naukrigulf.com", "forasna.com");

    private record Candidate(long id, String url, Instant firstSeenAt, Instant postedAt) {}

    private final JdbcTemplate jdbc;
    private final HttpFetcher http;
    private final PostingPublisher publisher;
    private final Clock clock;

    public DescriptionEnricher(JdbcTemplate jdbc, HttpFetcher http, PostingPublisher publisher, Clock clock) {
        this.jdbc = jdbc;
        this.http = http;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    public void run() {
        try {
            for (Candidate candidate : candidates()) {
                enrich(candidate);
            }
        } catch (RuntimeException e) {
            log.warn("Description enrichment failed", e);
        }
    }

    private List<Candidate> candidates() {
        return jdbc.query(
                """
                SELECT id, apply_target, first_seen_at, posted_at FROM posting
                WHERE enriched_at IS NULL AND apply_target ~* '^https?://'
                  AND char_length(coalesce(description, '')) < ?
                  AND first_seen_at > now() - interval '8 days'
                ORDER BY first_seen_at DESC LIMIT ?
                """,
                (rs, n) -> new Candidate(
                        rs.getLong("id"), rs.getString("apply_target"), rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant()),
                THIN_BELOW, BATCH);
    }

    void enrich(Candidate candidate) {
        URI uri;
        try {
            uri = URI.create(candidate.url());
        } catch (IllegalArgumentException e) {
            mark(candidate.id(), "failed");
            return;
        }
        if (blocked(uri)) {
            mark(candidate.id(), "blocked");
            return;
        }
        try {
            if (!robotsAllow(uri)) {
                mark(candidate.id(), "blocked");
                return;
            }
            FetchResponse page = http.get(uri);
            String text = page.isSuccess() ? PageText.extract(page.body()) : null;
            if (text == null || text.length() < THIN_BELOW) {
                mark(candidate.id(), page.isSuccess() ? "thin" : "failed");
                return;
            }
            jdbc.update(
                    "UPDATE posting SET description = ?, embedding = NULL, enriched_at = ?, enrich_status = 'full' WHERE id = ?",
                    text.length() > 20000 ? text.substring(0, 20000) : text, Timestamp.from(clock.instant()), candidate.id());
            // The full page may name a Google Form or an e-mail address; keep the page link as source_url.
            ApplyMethodDetector.detect(text).ifPresent(m -> jdbc.update(
                    "UPDATE posting SET source_url = apply_target, apply_channel = ?, apply_target = ? WHERE id = ? AND source_url IS NULL"
                            + " AND (apply_channel IS NULL OR apply_channel IN ('platform', 'ats_form'))",
                    m.channel(), m.target(), candidate.id()));
            Instant seen = candidate.firstSeenAt();
            PostingQueue queue = PostingAge.isHot(candidate.postedAt(), seen, clock.instant()) ? PostingQueue.HOT : PostingQueue.BACKLOG;
            publisher.publish(new PostingIngested(candidate.id(), seen, candidate.postedAt()), queue);
        } catch (SourceFetchException e) {
            mark(candidate.id(), "failed");
        }
    }

    public static boolean blocked(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.isEmpty() || BLOCKED_HOSTS.stream().anyMatch(b -> host.equals(b) || host.endsWith("." + b));
    }

    private boolean robotsAllow(URI page) {
        FetchResponse robots = http.get(page.resolve("/robots.txt"));
        String path = page.getRawPath() == null || page.getRawPath().isEmpty() ? "/" : page.getRawPath();
        return !robots.isSuccess() || RobotsRules.allows(robots.body(), AGENT, path);
    }

    private void mark(long id, String status) {
        jdbc.update("UPDATE posting SET enriched_at = ?, enrich_status = ? WHERE id = ?", Timestamp.from(clock.instant()), status, id);
    }
}
