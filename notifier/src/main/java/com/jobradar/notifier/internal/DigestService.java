package com.jobradar.notifier.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Weekly digest (M4): what was found, what was applied to, and which sources and CVs got
 * replies. One Telegram message per linked user, every Sunday evening Cairo time.
 *
 * <p>"Replied" counts every application whose status moved past {@code applied} to a reply
 * ({@code replied}, {@code interview} or {@code offer}); a rejection is also a response but is
 * listed separately so it does not inflate the reply rate.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    static final Duration WINDOW = Duration.ofDays(7);

    /** One row of a "by source" or "by CV" breakdown. */
    record Row(String label, int applied, int replies) {}

    record Digest(int newMatches, int applied, int replies, int rejections, int followUpsDue, List<Row> bySource, List<Row> byCv) {}

    private record Recipient(long userId, long chatId) {}

    private final JdbcTemplate jdbc;
    private final TelegramClient telegram;
    private final Clock clock;

    DigestService(JdbcTemplate jdbc, TelegramClient telegram, Clock clock) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.clock = clock;
    }

    @Scheduled(cron = "${jobradar.notifier.digest-cron:0 0 19 * * SUN}", zone = "Africa/Cairo")
    public int sendWeekly() {
        Instant now = clock.instant();
        List<Recipient> recipients = jdbc.query(
                "SELECT id, telegram_chat_id FROM app_user WHERE telegram_chat_id IS NOT NULL ORDER BY id",
                (rs, n) -> new Recipient(rs.getLong("id"), rs.getLong("telegram_chat_id")));
        int sent = 0;
        for (Recipient recipient : recipients) {
            try {
                telegram.sendMessage(recipient.chatId(), format(build(recipient.userId(), now)), null, null);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not send the weekly digest to user {}", recipient.userId(), e);
            }
        }
        return sent;
    }

    Digest build(long userId, Instant now) {
        Timestamp since = Timestamp.from(now.minus(WINDOW));
        int newMatches = count(
                "SELECT count(*) FROM match m JOIN posting p ON p.id = m.posting_id WHERE m.user_id = ? AND m.eligible AND p.first_seen_at >= ?",
                userId, since);
        // Applications sent in the window. Status 'sending' is never counted.
        String applied = "FROM application a WHERE a.user_id = ? AND a.sent_at >= ? AND a.status <> 'sending'";
        int appliedCount = count("SELECT count(*) " + applied, userId, since);
        int replies = count("SELECT count(*) " + applied + " AND a.status IN ('replied', 'interview', 'offer')", userId, since);
        int rejections = count("SELECT count(*) " + applied + " AND a.status = 'rejected'", userId, since);
        int followUpsDue = count(
                "SELECT count(*) FROM application WHERE user_id = ? AND status = 'applied' AND follow_up_at <= ? AND follow_up_notified_at IS NOT NULL",
                userId, Timestamp.from(now));

        List<Row> bySource = rows(
                """
                SELECT p.source AS label, count(*) AS applied,
                       count(*) FILTER (WHERE a.status IN ('replied', 'interview', 'offer')) AS replies
                FROM application a JOIN posting p ON p.id = a.posting_id
                WHERE a.user_id = ? AND a.sent_at >= ? AND a.status <> 'sending'
                GROUP BY p.source ORDER BY replies DESC, applied DESC, label
                """,
                userId, since);
        // A CV version is made for one posting, so it is named by that posting.
        List<Row> byCv = rows(
                """
                SELECT 'CV #' || v.id || ' (' || p.title || ')' AS label, count(*) AS applied,
                       count(*) FILTER (WHERE a.status IN ('replied', 'interview', 'offer')) AS replies
                FROM application a
                JOIN cv_version v ON v.id = a.cv_version_id
                JOIN posting p ON p.id = a.posting_id
                WHERE a.user_id = ? AND a.sent_at >= ? AND a.status <> 'sending'
                GROUP BY v.id, p.title ORDER BY replies DESC, v.id
                """,
                userId, since);
        return new Digest(newMatches, appliedCount, replies, rejections, followUpsDue, bySource, byCv);
    }

    static String format(Digest digest) {
        StringBuilder text = new StringBuilder("📊 Your week\n\n")
                .append(digest.newMatches()).append(" new matches\n")
                .append(digest.applied()).append(" applications sent\n")
                .append(digest.replies()).append(" replies");
        if (digest.applied() > 0) {
            text.append(" (").append(Math.round(digest.replies() * 100.0 / digest.applied())).append("%)");
        }
        text.append('\n').append(digest.rejections()).append(" rejections\n");
        if (digest.followUpsDue() > 0) {
            text.append(digest.followUpsDue()).append(" follow-ups waiting for your answer\n");
        }
        section(text, "Replies by source", digest.bySource());
        section(text, "Replies by CV", digest.byCv());
        return text.toString().strip();
    }

    private static void section(StringBuilder text, String title, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        text.append('\n').append(title).append('\n');
        for (Row row : rows.subList(0, Math.min(rows.size(), 5))) {
            text.append("• ").append(row.label()).append(": ").append(row.replies()).append(" of ").append(row.applied()).append('\n');
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private List<Row> rows(String sql, Object... args) {
        return new ArrayList<>(jdbc.query(
                sql, (rs, n) -> new Row(rs.getString("label"), rs.getInt("applied"), rs.getInt("replies")), args));
    }
}
