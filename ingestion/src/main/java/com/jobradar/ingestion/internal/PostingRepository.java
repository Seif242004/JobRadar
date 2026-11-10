package com.jobradar.ingestion.internal;

import com.jobradar.ingestion.RawPosting;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the shared {@code posting}, {@code company} and {@code source_run} tables. */
@Repository
public class PostingRepository {

    private final JdbcTemplate jdbc;

    PostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when a different source already stored the same job: same apply URL, or same content
     * hash. The same (source, source_id) is not a cross-source duplicate; {@link #insert} handles it.
     */
    public boolean existsFromAnotherSource(String source, String sourceId, String applyTarget, String contentHash) {
        // An email address is not compared: one HR mailbox receives many different jobs.
        String url = PostingNormalizer.isHttpUrl(applyTarget) ? applyTarget : null;
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM posting
                    WHERE NOT (source = ? AND source_id = ?)
                      AND (content_hash = ? OR (?::text IS NOT NULL AND apply_target = ?)))
                """,
                Boolean.class,
                source, sourceId, contentHash, url, url);
        return Boolean.TRUE.equals(exists);
    }

    /** Inserts the posting; empty when (source, source_id) is already stored. */
    public OptionalLong insert(RawPosting raw, String applyTarget, String contentHash, Instant firstSeenAt) {
        Long companyId = raw.companyName() == null || raw.companyName().isBlank()
                ? null
                : companyId(raw.companyName().strip());
        List<Long> ids = jdbc.queryForList(
                """
                INSERT INTO posting (company_id, source, source_id, title, location, remote_policy, language,
                                     description, apply_channel, apply_target, deadline, posted_at,
                                     first_seen_at, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_id) DO NOTHING
                RETURNING id
                """,
                Long.class,
                companyId, raw.source(), raw.sourceId(), raw.title(), raw.location(), raw.remotePolicy(),
                raw.language(), raw.description(), raw.applyChannel(), applyTarget,
                timestamp(raw.deadline()), timestamp(raw.postedAt()), timestamp(firstSeenAt), contentHash);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    /** Finds the company by name, creating it when it is new. */
    private Long companyId(String name) {
        // DO UPDATE (a no-op write) instead of DO NOTHING so RETURNING yields the existing row too.
        return jdbc.queryForObject(
                """
                INSERT INTO company (name) VALUES (?)
                ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """,
                Long.class,
                name);
    }

    public void recordRun(String source, Instant startedAt, int itemsFound, int newItems, List<String> errors) {
        jdbc.update(
                "INSERT INTO source_run (source, started_at, items_found, new_items, errors) VALUES (?, ?, ?, ?, ?)",
                source, timestamp(startedAt), itemsFound, newItems, errors.isEmpty() ? null : String.join("\n", errors));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
