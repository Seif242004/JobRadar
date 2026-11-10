package com.jobradar.notifier.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Follow-up reminders (plan: 7 to 10 days after applying, no reply). Sends one reminder per
 * application with a draft the user can copy. The system never sends the follow-up itself.
 */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.of("Africa/Cairo"));

    record Due(long applicationId, long chatId, String title, String company, String name, Instant sentAt) {}

    private final JdbcTemplate jdbc;
    private final TelegramClient telegram;
    private final Clock clock;

    FollowUpService(JdbcTemplate jdbc, TelegramClient telegram, Clock clock) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.clock = clock;
    }

    @Scheduled(cron = "${jobradar.notifier.follow-up-cron:0 15 9-20 * * *}", zone = "Africa/Cairo")
    public int sendDue() {
        Instant now = clock.instant();
        List<Due> due = jdbc.query(
                """
                SELECT a.id, u.telegram_chat_id, p.title, c.name AS company, pr.cv_static ->> 'name' AS name, a.sent_at
                FROM application a
                JOIN app_user u ON u.id = a.user_id
                JOIN posting p ON p.id = a.posting_id
                LEFT JOIN company c ON c.id = p.company_id
                LEFT JOIN profile pr ON pr.user_id = a.user_id
                WHERE a.status = 'applied' AND a.follow_up_at <= ? AND a.follow_up_notified_at IS NULL
                  AND u.telegram_chat_id IS NOT NULL
                ORDER BY a.follow_up_at
                """,
                (rs, n) -> new Due(
                        rs.getLong("id"), rs.getLong("telegram_chat_id"), rs.getString("title"),
                        rs.getString("company"), rs.getString("name"), rs.getTimestamp("sent_at").toInstant()),
                Timestamp.from(now));
        int sent = 0;
        for (Due item : due) {
            try {
                telegram.sendWithButtons(
                        item.chatId(),
                        message(item),
                        List.of(List.of(
                                TelegramClient.Button.action("Got a reply", TapData.replied(item.applicationId())),
                                TelegramClient.Button.action("Rejected", TapData.rejected(item.applicationId())))));
                // Marked only after a successful send, so a failure is retried at the next run.
                jdbc.update("UPDATE application SET follow_up_notified_at = ? WHERE id = ?", Timestamp.from(now), item.applicationId());
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not send the follow-up reminder for application {}", item.applicationId(), e);
            }
        }
        return sent;
    }

    static String message(Due item) {
        String role = item.company() == null ? item.title() : item.title() + " · " + item.company();
        String signature = item.name() == null ? "" : item.name();
        return "📨 Follow up: " + role + "\nYou applied on " + DAY.format(item.sentAt()) + " and no reply is recorded.\n\n"
                + "Draft:\n"
                + "Dear Hiring Team,\n\n"
                + "I applied for the " + item.title() + " position on " + DAY.format(item.sentAt())
                + " and wanted to follow up on my application. I remain very interested in the role and would be glad to provide anything further you need.\n\n"
                + "Best regards,\n" + signature;
    }
}
