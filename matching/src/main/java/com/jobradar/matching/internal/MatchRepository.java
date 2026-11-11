package com.jobradar.matching.internal;

import com.jobradar.matching.Band;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchRepository {

    private final JdbcTemplate jdbc;

    MatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PostingView> findPosting(long postingId) {
        return jdbc.query(
                        """
                        SELECT id, title, location, remote_policy, description, deadline, posted_at, first_seen_at
                        FROM posting WHERE id = ?
                        """,
                        (rs, rowNum) -> new PostingView(
                                rs.getLong("id"),
                                rs.getString("title"),
                                rs.getString("location"),
                                rs.getString("remote_policy"),
                                rs.getString("description"),
                                instant(rs.getTimestamp("deadline")),
                                instant(rs.getTimestamp("posted_at")),
                                instant(rs.getTimestamp("first_seen_at"))),
                        postingId)
                .stream()
                .findFirst();
    }

    /** Stored verdict per posting for one user. */
    public java.util.Map<Long, Boolean> eligibilityByPosting(long userId) {
        java.util.Map<Long, Boolean> map = new java.util.HashMap<>();
        jdbc.query("SELECT posting_id, eligible FROM match WHERE user_id = ?",
                rs -> { map.put(rs.getLong("posting_id"), rs.getBoolean("eligible")); }, userId);
        return map;
    }

    public java.util.List<Long> allPostingIds() {
        return jdbc.queryForList("SELECT id FROM posting ORDER BY id", Long.class);
    }

    /** Idempotent: a redelivered queue message rewrites the same row. */
    public void upsert(
            long userId, long postingId, boolean eligible, Double fitScore, Double priority, Band band, String reasons) {
        jdbc.update(
                """
                INSERT INTO match (user_id, posting_id, eligible, fit_score, priority, band, reasons)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, posting_id) DO UPDATE SET
                    eligible = EXCLUDED.eligible, fit_score = EXCLUDED.fit_score,
                    priority = EXCLUDED.priority, band = EXCLUDED.band, reasons = EXCLUDED.reasons
                """,
                userId, postingId, eligible, fitScore, priority, band.dbValue(), reasons);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
