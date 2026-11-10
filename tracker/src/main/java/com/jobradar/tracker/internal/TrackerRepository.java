package com.jobradar.tracker.internal;

import com.jobradar.tracker.ApplicationStatus;
import com.jobradar.tracker.TrackedApplication;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrackerRepository {

    private final JdbcTemplate jdbc;

    TrackerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean updateStatus(long userId, long applicationId, ApplicationStatus status) {
        return jdbc.update(
                        // 'sending' rows are owned by the apply module until the send finishes.
                        "UPDATE application SET status = ? WHERE id = ? AND user_id = ? AND status <> 'sending'",
                        status.dbValue(), applicationId, userId)
                > 0;
    }

    public List<TrackedApplication> recent(long userId, int limit) {
        return jdbc.query(
                """
                SELECT a.id, a.posting_id, p.title, c.name AS company, a.channel, a.status, a.sent_at, a.follow_up_at
                FROM application a
                JOIN posting p ON p.id = a.posting_id
                LEFT JOIN company c ON c.id = p.company_id
                WHERE a.user_id = ? AND a.status <> 'sending'
                ORDER BY a.sent_at DESC NULLS LAST, a.id DESC
                LIMIT ?
                """,
                (rs, n) -> {
                    Timestamp sentAt = rs.getTimestamp("sent_at");
                    Timestamp followUpAt = rs.getTimestamp("follow_up_at");
                    return new TrackedApplication(
                            rs.getLong("id"), rs.getLong("posting_id"), rs.getString("title"), rs.getString("company"),
                            rs.getString("channel"),
                            ApplicationStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                            sentAt == null ? null : sentAt.toInstant(),
                            followUpAt == null ? null : followUpAt.toInstant());
                },
                userId, limit);
    }
}
