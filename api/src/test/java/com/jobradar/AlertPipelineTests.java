package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jobradar.matching.internal.PostingMatcher;
import com.jobradar.notifier.internal.AlertService;
import com.jobradar.notifier.internal.TelegramClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Posting to alert, inside the api: hard filters, match row, and the push rules per freshness
 * band. Telegram is replaced by a mock (declared on the base class); everything else is real.
 */
class AlertPipelineTests extends PostgresIntegrationTest {

    private static final long CHAT_ID = 4242L;

    @Autowired
    PostingMatcher matcher;

    @Autowired
    AlertService alerts;

    @Autowired
    JdbcTemplate jdbc;

    long userId;

    @BeforeEach
    void seed() {
        reset(telegram);
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES ('a@example.com', ?, 5) RETURNING id",
                Long.class, CHAT_ID);
        jdbc.update(
                """
                INSERT INTO profile (user_id, roles, cities, remote, job_types, graduation_date)
                VALUES (?, '{Backend Engineer}', '{Cairo}', TRUE, '{internship,full-time}', DATE '2027-06-30')
                """,
                userId);
    }

    /** Inserts a posting first seen {@code age} ago. */
    private long posting(String title, String location, Duration age, Instant deadline) {
        Long companyId = jdbc.queryForObject(
                "INSERT INTO company (name) VALUES ('Instabug') ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                Long.class);
        return jdbc.queryForObject(
                """
                INSERT INTO posting (company_id, source, source_id, title, location, apply_channel, apply_target,
                                     deadline, first_seen_at, content_hash, description)
                VALUES (?, 'fixture', ?, ?, ?, 'ats_form', ?, ?, ?, ?, 'Open to fresh graduates.') RETURNING id
                """,
                Long.class,
                companyId, title + age, title, location, "https://example.com/jobs/" + title.hashCode(),
                deadline == null ? null : Timestamp.from(deadline),
                Timestamp.from(Instant.now().minus(age)), title + age);
    }

    @Test
    void aFreshEligiblePostingIsPushedAtOnceAndOnlyOnce() {
        long id = posting("Backend Intern", "Cairo, Egypt", Duration.ofMinutes(5), null);

        matcher.match(id);
        // A redelivered queue message must not push again.
        matcher.match(id);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendMessage(eq(CHAT_ID), text.capture(), eq("Open posting"), contains("example.com/jobs/"));
        assertThat(text.getValue()).contains("Apply now", "Backend Intern · Instabug · Cairo, Egypt", "In Egypt, internship");
        assertThat(jdbc.queryForMap("SELECT eligible, band, fit_score FROM match WHERE posting_id = ?", id))
                .containsEntry("eligible", true)
                .containsEntry("band", "hot")
                .containsEntry("fit_score", null);
        assertThat(jdbc.queryForObject("SELECT type FROM notification WHERE posting_id = ?", String.class, id))
                .isEqualTo("apply_now");
    }

    @Test
    void anIneligiblePostingIsStoredWithItsReasonAndNeverPushed() {
        long id = posting("Senior Backend Engineer", "Cairo, Egypt", Duration.ofMinutes(5), null);

        matcher.match(id);
        alerts.sendWarmBatches();

        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT reasons FROM match WHERE posting_id = ?", String.class, id))
                .isEqualTo("Senior role for an entry-level profile");
    }

    @Test
    void warmPostingsArriveInOneBatchAndBacklogIsNeverPushed() {
        long warmA = posting("Backend Engineer", "Cairo", Duration.ofHours(3), null);
        long warmB = posting("Data Engineer", "Remote", Duration.ofHours(20), null);
        long backlog = posting("QA Engineer", "Cairo", Duration.ofHours(30), null);
        for (long id : new long[] {warmA, warmB, backlog}) {
            matcher.match(id);
        }
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());

        alerts.sendWarmBatches();
        // The next run has nothing left to send.
        alerts.sendWarmBatches();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendMessage(eq(CHAT_ID), text.capture(), isNull(), isNull());
        assertThat(text.getValue())
                .contains("New matches in the last 24 hours (2)", "Backend Engineer", "Data Engineer")
                .doesNotContain("QA Engineer");
        // Newest first while no fit score exists.
        assertThat(text.getValue().indexOf("Backend Engineer")).isLessThan(text.getValue().indexOf("Data Engineer"));
    }

    @Test
    void aDeadlinePostingIsPushedOnceWhenAtMostThreeDaysRemain() {
        Instant now = Instant.now();
        long far = posting("Summer Internship", "Cairo", Duration.ofMinutes(5), now.plus(Duration.ofDays(10)));
        long near = posting("Bootcamp Internship", "Cairo", Duration.ofDays(6), now.plus(Duration.ofDays(2)));
        matcher.match(far);
        matcher.match(near);
        alerts.sendWarmBatches();
        // Exempt from the hot push and from the warm batch.
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());

        alerts.sendDeadlineAlerts();
        alerts.sendDeadlineAlerts();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendMessage(eq(CHAT_ID), text.capture(), any(), any());
        assertThat(text.getValue()).contains("Deadline soon", "Bootcamp Internship");
    }

    @Test
    void aFailedHotPushIsNotRecordedAndIsRetriedByTheWarmBatch() {
        long id = posting("Backend Intern", "Cairo", Duration.ofMinutes(119), null);
        doThrow(new IllegalStateException("Telegram is unreachable"))
                .when(telegram).sendMessage(anyLong(), contains("Apply now"), any(), any());

        try {
            matcher.match(id);
        } catch (IllegalStateException expected) {
            // The queue listener leaves the message for redelivery.
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isZero();

        // Two hours later the posting is warm and still unsent, so the batch carries it.
        jdbc.update("UPDATE posting SET first_seen_at = first_seen_at - INTERVAL '2 hours' WHERE id = ?", id);
        alerts.sendWarmBatches();

        verify(telegram).sendMessage(eq(CHAT_ID), contains("New matches"), isNull(), isNull());
    }

    @Test
    void usersWithoutALinkedTelegramChatGetNoPush() {
        jdbc.update("UPDATE app_user SET telegram_chat_id = NULL WHERE id = ?", userId);

        matcher.match(posting("Backend Intern", "Cairo", Duration.ofMinutes(5), null));

        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match", Long.class)).isEqualTo(1);
    }

    @Test
    void topPicksPushTheBestOldMatchesOnceAndMoveOnToTheNextOnes() {
        // Old postings are Backlog: no alert covers them, so only the top-picks run can announce them.
        long[] ids = new long[7];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = posting("Backend Engineer " + i, "Cairo", Duration.ofDays(3 + i), null);
            matcher.match(ids[i]);
            jdbc.update("UPDATE match SET fit_score = ?, priority = ? WHERE posting_id = ?", 90 - i, 90 - i, ids[i]);
        }
        long weak = posting("Backend Engineer weak", "Cairo", Duration.ofDays(9), null);
        matcher.match(weak);
        jdbc.update("UPDATE match SET fit_score = 40, priority = 40 WHERE posting_id = ?", weak);
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());

        alerts.sendTopPicks();
        alerts.sendTopPicks();
        alerts.sendTopPicks();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(2)).sendMessage(eq(CHAT_ID), text.capture(), any(), any());
        // First run: the five best. Second run: the remaining two. Third run: nothing. The weak one never.
        assertThat(text.getAllValues().get(0)).contains("Top picks for you (5)", "Backend Engineer 0 ", "fit 90").doesNotContain("Backend Engineer 5");
        assertThat(text.getAllValues().get(1)).contains("Top picks for you (2)", "Backend Engineer 5", "Backend Engineer 6");
        assertThat(text.getAllValues()).noneMatch(t -> t.contains("weak"));
    }
}
