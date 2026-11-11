package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobradar.llm.EmbeddingClient;
import com.jobradar.matching.Band;
import com.jobradar.matching.MatchScored;
import com.jobradar.matching.internal.PostingMatcher;
import com.jobradar.notifier.internal.AlertService;
import com.jobradar.notifier.internal.TelegramClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SPEC.md D7: the Apply-now push needs fit 85+, a posting under 1 hour old and a ready CV. The
 * CV itself comes from the tailoring-worker; here its result is a {@code cv_version} row.
 */
class ApplyNowTests extends PostgresIntegrationTest {

    private static final long CHAT_ID = 99L;
    private static final String LINK = "https://cv.example/signed.pdf";

    @Autowired
    AlertService alerts;

    @Autowired
    PostingMatcher matcher;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcTemplate jdbc;

    long userId;

    @BeforeEach
    void seed() {
        reset(telegram, tailorRequests, cvLinks);
        when(cvLinks.presign(any())).thenReturn(LINK);
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES ('d@example.com', ?, 5) RETURNING id",
                Long.class, CHAT_ID);
        jdbc.update(
                """
                INSERT INTO profile (user_id, roles, cities, remote, job_types, graduation_date)
                VALUES (?, '{Backend Engineer}', '{Cairo}', TRUE, '{internship,full-time}', DATE '2027-06-30')
                """,
                userId);
    }

    /** A posting with a stored match and a rendered CV; returns {postingId, cvVersionId}. */
    private long[] postingWithCv(Duration age, double fit) {
        long posting = jdbc.queryForObject(
                """
                INSERT INTO posting (source, source_id, title, location, apply_channel, apply_target, first_seen_at, content_hash)
                VALUES ('fixture', ?, 'Backend Engineer', 'Cairo, Egypt', 'ats_form', 'https://example.com/j', ?, ?) RETURNING id
                """,
                Long.class, "p" + age + fit, Timestamp.from(Instant.now().minus(age)), "h" + age + fit);
        jdbc.update(
                "INSERT INTO match (user_id, posting_id, eligible, fit_score, band, reasons) VALUES (?, ?, TRUE, ?, 'hot', 'In Egypt, full-time')",
                userId, posting, fit);
        long cv = jdbc.queryForObject(
                "INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key, ats_score) VALUES (?, ?, '{1}', ?, 62.5) RETURNING id",
                Long.class, userId, posting, "cv/" + userId + "/" + posting + "/x.pdf");
        return new long[] {posting, cv};
    }

    @Test
    void aReadyCvForAStrongFreshMatchIsPushedOnceWithTheCvLink() {
        long[] ids = postingWithCv(Duration.ofMinutes(20), 90);

        assertThat(alerts.onCvReady(userId, ids[0], ids[1], 62.5)).isTrue();
        // A redelivered CV-ready message must not push again.
        assertThat(alerts.onCvReady(userId, ids[0], ids[1], 62.5)).isFalse();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<TelegramClient.Button>>> rows = ArgumentCaptor.forClass(List.class);
        verify(telegram, times(1)).sendWithButtons(eq(CHAT_ID), text.capture(), rows.capture());
        assertThat(text.getValue()).contains("Apply now", "Fit 90", "ATS keywords 63%", LINK);
        // The posting is an ATS form: the user applies there and taps "I applied". Nothing is sent.
        assertThat(rows.getValue().get(0)).containsExactly(TelegramClient.Button.link("Open posting", "https://example.com/j"));
        assertThat(rows.getValue().get(1))
                .extracting(TelegramClient.Button::label)
                .containsExactly("I applied", "Skip", "Later");
        verify(cvLinks).presign("cv/" + userId + "/" + ids[0] + "/x.pdf");
        assertThat(jdbc.queryForObject("SELECT type FROM notification WHERE posting_id = ?", String.class, ids[0]))
                .isEqualTo("apply_now");
    }

    @Test
    void aFitBelow85IsNotPushedEvenWithACv() {
        long[] ids = postingWithCv(Duration.ofMinutes(20), 84);

        assertThat(alerts.onCvReady(userId, ids[0], ids[1], 50)).isFalse();

        nothingPushed();
    }

    @Test
    void aPostingOlderThanOneHourIsNotPushedEvenWithACv() {
        long[] ids = postingWithCv(Duration.ofMinutes(75), 95);

        assertThat(alerts.onCvReady(userId, ids[0], ids[1], 50)).isFalse();

        nothingPushed();
    }

    @Test
    void aCvVersionOfAnotherUserIsNeverLinked() {
        long[] ids = postingWithCv(Duration.ofMinutes(20), 90);
        long other = jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES ('o@example.com', 5, 5) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO match (user_id, posting_id, eligible, fit_score, band) VALUES (?, ?, TRUE, 90, 'hot')", other, ids[0]);

        // The message claims the other user's match but names this user's CV version.
        assertThat(alerts.onCvReady(other, ids[0], ids[1], 50)).isFalse();

        nothingPushed();
        verify(cvLinks, never()).presign(any());
    }

    @Test
    void aHotMatchWithAFitScoreWaitsForItsCv() {
        long[] ids = postingWithCv(Duration.ofMinutes(5), 92);

        events.publishEvent(new MatchScored(userId, ids[0], true, Band.HOT, 92.0));

        nothingPushed();
    }

    @Test
    void aHotMatchWithoutAFitScoreIsStillPushedAtOnce() {
        long[] ids = postingWithCv(Duration.ofMinutes(5), 50);

        // No fit score (embeddings unavailable): no CV will follow, so push now (SPEC.md D8).
        events.publishEvent(new MatchScored(userId, ids[0], true, Band.HOT, null));

        verify(telegram, times(1)).sendMessage(eq(CHAT_ID), contains("Apply now"), any(), any());
    }

    @Test
    void theMatcherRequestsCvsForHotAndWarmMatchesWithAFitOnly() {
        // Every text is "backend": cosine 1, so the fit is 100 without an LLM call.
        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            List<?> texts = call.getArgument(0);
            return texts.stream().map(t -> backend()).toList();
        });
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'experience', 'Built backend services')", userId);
        long hot = insertPosting("Backend Engineer", Duration.ofMinutes(10));
        long warm = insertPosting("Backend Developer", Duration.ofHours(5));
        long backlog = insertPosting("Backend Engineering Intern", Duration.ofHours(40));

        for (long id : new long[] {hot, warm, backlog}) {
            matcher.match(id);
        }

        verify(tailorRequests).publish(userId, hot, true);
        verify(tailorRequests).publish(userId, warm, false);
        // Backlog (over 24 hours) is never tailored automatically.
        verify(tailorRequests, never()).publish(eq(userId), eq(backlog), anyBoolean());
    }

    @Test
    void aFailedTailorRequestDoesNotFailTheMatch() {
        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            List<?> texts = call.getArgument(0);
            return texts.stream().map(t -> backend()).toList();
        });
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'experience', 'Built backend services')", userId);
        doThrow(new IllegalStateException("sqs down")).when(tailorRequests).publish(anyLong(), anyLong(), anyBoolean());
        long id = insertPosting("Backend Engineer", Duration.ofMinutes(10));

        assertThat(matcher.match(id)).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM match WHERE posting_id = ?", Integer.class, id)).isEqualTo(1);
    }

    private void nothingPushed() {
        verify(telegram, never()).sendMessage(anyLong(), any(), any(), any());
        verify(telegram, never()).sendWithButtons(anyLong(), any(), any());
    }

    private static float[] backend() {
        float[] vector = new float[EmbeddingClient.DIMENSIONS];
        vector[0] = 1f;
        return vector;
    }

    private long insertPosting(String title, Duration age) {
        return jdbc.queryForObject(
                """
                INSERT INTO posting (source, source_id, title, location, description, apply_channel, apply_target, first_seen_at, content_hash)
                VALUES ('fixture', ?, ?, 'Cairo, Egypt', 'Backend role for fresh graduates', 'ats_form', 'https://example.com/j', ?, ?) RETURNING id
                """,
                Long.class, title, title, Timestamp.from(Instant.now().minus(age)), title);
    }
}
