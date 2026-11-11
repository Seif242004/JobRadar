package com.jobradar.notifier.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Finds what is due for an alert. Every query excludes postings the user was already notified
 * about, so no posting interrupts the user twice, and users without a linked Telegram chat.
 */
@Repository
public class NotificationRepository {

    private static final String ALERT_COLUMNS =
            """
            SELECT m.user_id, u.telegram_chat_id, p.id AS posting_id, p.title, c.name AS company, p.location,
                   p.apply_channel, p.apply_target, p.deadline, m.reasons, m.fit_score,
                   LEAST(p.posted_at, p.first_seen_at) AS fresh_since
            FROM match m
            JOIN app_user u ON u.id = m.user_id
            JOIN posting p ON p.id = m.posting_id
            LEFT JOIN company c ON c.id = p.company_id
            WHERE m.eligible AND u.telegram_chat_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.user_id = m.user_id AND n.posting_id = p.id)
            """;

    private static final RowMapper<AlertPosting> MAPPER = NotificationRepository::map;

    private final JdbcTemplate jdbc;

    NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One eligible, not yet notified match. */
    public Optional<AlertPosting> findPending(long userId, long postingId) {
        return jdbc.query(ALERT_COLUMNS + " AND m.user_id = ? AND p.id = ?", MAPPER, userId, postingId).stream()
                .findFirst();
    }

    /**
     * Postings without a deadline whose age (from the earlier of posted_at and first_seen_at) lies
     * in [{@code oldest}, {@code newest}), best first: by priority once fit scores exist, then newest.
     */
    public List<AlertPosting> findPendingByAge(Instant oldest, Instant newest) {
        return jdbc.query(
                ALERT_COLUMNS
                        + """
                         AND p.deadline IS NULL
                         AND LEAST(p.posted_at, p.first_seen_at) > ? AND LEAST(p.posted_at, p.first_seen_at) <= ?
                        ORDER BY m.user_id, m.priority DESC NULLS LAST, LEAST(p.posted_at, p.first_seen_at) DESC
                        """,
                MAPPER,
                Timestamp.from(oldest), Timestamp.from(newest));
    }

    /**
     * Eligible matches with a fit score of at least {@code minFit} that nobody was told about yet,
     * of any age, best first per user. Fills the gap the age-based alerts leave: postings older
     * than 24 hours, or matches that appeared after the user changed preferences.
     */
    public List<AlertPosting> findPendingTop(double minFit) {
        return jdbc.query(
                ALERT_COLUMNS + " AND m.fit_score >= ? ORDER BY m.user_id, m.priority DESC NULLS LAST, m.fit_score DESC",
                MAPPER,
                minFit);
    }

    /** Postings whose deadline falls in ({@code now}, {@code until}], soonest first. */
    public List<AlertPosting> findPendingByDeadline(Instant now, Instant until) {
        return jdbc.query(
                ALERT_COLUMNS + " AND p.deadline > ? AND p.deadline <= ? ORDER BY m.user_id, p.deadline",
                MAPPER,
                Timestamp.from(now), Timestamp.from(until));
    }

    public void recordSent(long userId, long postingId, NotificationType type, Instant sentAt) {
        jdbc.update(
                "INSERT INTO notification (user_id, type, posting_id, sent_at) VALUES (?, ?, ?, ?)",
                userId, type.dbValue(), postingId, Timestamp.from(sentAt));
    }

    /** The user linked to this Telegram chat, or empty. */
    public Optional<Long> findUserByChat(long chatId) {
        return jdbc.queryForList("SELECT id FROM app_user WHERE telegram_chat_id = ?", Long.class, chatId).stream()
                .findFirst();
    }

    /** Records the user's choice on the Apply-now alert; the first choice stands. */
    public void recordAction(long userId, long postingId, String action, Instant at) {
        jdbc.update(
                """
                UPDATE notification SET acted_at = ?, action = ?
                WHERE user_id = ? AND posting_id = ? AND type = 'apply_now' AND acted_at IS NULL
                """,
                Timestamp.from(at), action, userId, postingId);
    }

    /** The stored PDF of one of the user's own CV versions, or empty. */
    public Optional<String> findCvKey(long userId, long cvVersionId) {
        return jdbc.queryForList(
                        "SELECT pdf_s3_key FROM cv_version WHERE id = ? AND user_id = ? AND pdf_s3_key IS NOT NULL",
                        String.class, cvVersionId, userId)
                .stream()
                .findFirst();
    }

    private static AlertPosting map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp deadline = rs.getTimestamp("deadline");
        java.math.BigDecimal fit = rs.getBigDecimal("fit_score");
        return new AlertPosting(
                rs.getLong("user_id"),
                rs.getLong("telegram_chat_id"),
                rs.getLong("posting_id"),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("location"),
                rs.getString("apply_channel"),
                rs.getString("apply_target"),
                deadline == null ? null : deadline.toInstant(),
                rs.getString("reasons"),
                fit == null ? null : fit.doubleValue(),
                rs.getTimestamp("fresh_since").toInstant());
    }
}
