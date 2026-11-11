package com.jobradar.apply.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApplyRepository {

    private final JdbcTemplate jdbc;

    ApplyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Empty unless the posting exists and the CV version belongs to this user and this posting,
     * so a tap can never attach someone else's CV or apply with a CV made for another job.
     */
    public Optional<ApplyContext> loadContext(long userId, long postingId, long cvVersionId) {
        return jdbc.query(
                        """
                        SELECT p.title, c.name AS company, p.apply_channel, p.apply_target, u.email, u.gmail_token_ref,
                               pr.cv_static ->> 'name' AS name, u.daily_apply_cap, v.pdf_s3_key
                        FROM cv_version v
                        JOIN app_user u ON u.id = v.user_id
                        JOIN posting p ON p.id = v.posting_id
                        LEFT JOIN company c ON c.id = p.company_id
                        LEFT JOIN profile pr ON pr.user_id = u.id
                        WHERE v.id = ? AND v.user_id = ? AND v.posting_id = ? AND v.pdf_s3_key IS NOT NULL
                        """,
                        (rs, n) -> new ApplyContext(
                                rs.getString("title"), rs.getString("company"), rs.getString("apply_channel"),
                                rs.getString("apply_target"), rs.getString("email"), rs.getString("gmail_token_ref"),
                                rs.getString("name"), rs.getInt("daily_apply_cap"), rs.getString("pdf_s3_key")),
                        cvVersionId, userId, postingId)
                .stream()
                .findFirst();
    }

    /** Emails sent or being sent since local midnight; manual applications do not count. */
    public int emailsSinceStartOfDay(long userId, Instant now, ZoneId zone) {
        Instant start = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM application
                WHERE user_id = ? AND channel = 'email' AND (status = 'sending' OR sent_at >= ?)
                """,
                Integer.class, userId, Timestamp.from(start));
    }

    /** Claims the posting for this user. Empty when an application already exists. */
    public Optional<Long> begin(long userId, long postingId, long cvVersionId, String channel) {
        List<Long> ids = jdbc.queryForList(
                """
                INSERT INTO application (user_id, posting_id, cv_version_id, channel, status)
                VALUES (?, ?, ?, ?, 'sending')
                ON CONFLICT (user_id, posting_id) DO NOTHING
                RETURNING id
                """,
                Long.class, userId, postingId, cvVersionId, channel);
        return ids.stream().findFirst();
    }

    public void complete(long applicationId, Instant sentAt, Instant followUpAt) {
        jdbc.update(
                "UPDATE application SET status = 'applied', sent_at = ?, follow_up_at = ? WHERE id = ?",
                Timestamp.from(sentAt), Timestamp.from(followUpAt), applicationId);
    }

    /** Only for a failed send; the status check keeps it from deleting a sent application. */
    public void abandon(long applicationId) {
        jdbc.update("DELETE FROM application WHERE id = ? AND status = 'sending'", applicationId);
    }

    /** @return false when an application for this posting already exists */
    public boolean insertManual(long userId, long postingId, long cvVersionId, Instant now, Instant followUpAt) {
        return jdbc.update(
                        """
                        INSERT INTO application (user_id, posting_id, cv_version_id, channel, status, sent_at, follow_up_at)
                        VALUES (?, ?, ?, 'manual', 'applied', ?, ?)
                        ON CONFLICT (user_id, posting_id) DO NOTHING
                        """,
                        userId, postingId, cvVersionId, Timestamp.from(now), Timestamp.from(followUpAt))
                > 0;
    }
}
