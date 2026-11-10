package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.jobradar.notifier.internal.DigestService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** M4 verify: the weekly digest shows replies by source and by CV version. */
class WeeklyDigestTests extends PostgresIntegrationTest {

    private static final long CHAT = 31L;

    @Autowired
    DigestService digest;

    @Autowired
    JdbcTemplate jdbc;

    long userId;
    int counter;

    @BeforeEach
    void seed() {
        reset(telegram);
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES ('w@example.com', ?, 5) RETURNING id",
                Long.class, CHAT);
    }

    /** An application on a posting from {@code source}, sent {@code daysAgo} days ago. */
    private void application(String source, String title, String status, int daysAgo) {
        long posting = jdbc.queryForObject(
                """
                INSERT INTO posting (source, source_id, title, first_seen_at, content_hash)
                VALUES (?, ?, ?, now() - interval '1 day', ?) RETURNING id
                """,
                Long.class, source, "s" + counter, title, "h" + counter++);
        jdbc.update("INSERT INTO match (user_id, posting_id, eligible, band) VALUES (?, ?, TRUE, 'warm')", userId, posting);
        long cv = jdbc.queryForObject(
                "INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key) VALUES (?, ?, '{1}', 'k') RETURNING id",
                Long.class, userId, posting);
        jdbc.update(
                "INSERT INTO application (user_id, posting_id, cv_version_id, channel, status, sent_at) VALUES (?, ?, ?, 'email', ?, ?)",
                userId, posting, cv, status, Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))));
    }

    @Test
    void theDigestCountsTheWeekAndBreaksRepliesDownBySourceAndCv() {
        application("greenhouse", "Backend Intern", "replied", 2);
        application("greenhouse", "Data Intern", "applied", 3);
        application("lever", "SRE Intern", "interview", 1);
        application("lever", "QA Intern", "rejected", 4);
        // Older than a week: not part of this digest.
        application("remotive", "Old Role", "replied", 10);

        assertThat(digest.sendWeekly()).isEqualTo(1);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq(CHAT), text.capture(), eq(null), eq(null));
        String message = text.getValue();
        assertThat(message).contains("4 applications sent", "2 replies (50%)", "1 rejections");
        // Lever and Greenhouse each got a reply; the old Remotive application is outside the window.
        assertThat(message).contains("Replies by source", "greenhouse: 1 of 2", "lever: 1 of 2").doesNotContain("remotive");
        assertThat(message).contains("Replies by CV", "(Backend Intern): 1 of 1", "(SRE Intern): 1 of 1", "(Data Intern): 0 of 1");
    }

    @Test
    void aQuietWeekStillProducesAShortDigest() {
        assertThat(digest.sendWeekly()).isEqualTo(1);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq(CHAT), text.capture(), eq(null), eq(null));
        assertThat(text.getValue()).contains("0 new matches", "0 applications sent", "0 replies").doesNotContain("%");
    }

    @Test
    void usersWithoutATelegramChatGetNothing() {
        jdbc.update("UPDATE app_user SET telegram_chat_id = NULL");

        assertThat(digest.sendWeekly()).isZero();
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
    }
}
