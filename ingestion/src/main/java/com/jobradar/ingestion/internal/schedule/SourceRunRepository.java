package com.jobradar.ingestion.internal.schedule;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the run history from {@code source_run}. The schedule is derived from this table, not
 * from memory, so a restart or a second worker cannot exceed a source's rate limit.
 */
@Repository
class SourceRunRepository {

    private final JdbcTemplate jdbc;

    SourceRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Start of the latest run of {@code source}; null when it never ran. */
    Instant lastRunAt(String source) {
        Timestamp last = jdbc.queryForObject(
                "SELECT max(started_at) FROM source_run WHERE source = ?", Timestamp.class, source);
        return last == null ? null : last.toInstant();
    }

    int runsSince(String source, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM source_run WHERE source = ? AND started_at > ?",
                Integer.class,
                source, Timestamp.from(since));
        return count == null ? 0 : count;
    }
}
