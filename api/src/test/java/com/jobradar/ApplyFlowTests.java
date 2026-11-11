package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobradar.apply.internal.GmailNotConnectedException;
import com.jobradar.apply.internal.OutgoingEmail;
import com.jobradar.notifier.internal.DecisionHandler;
import com.jobradar.notifier.internal.FollowUpService;
import com.jobradar.notifier.internal.TelegramClient;
import com.jobradar.notifier.internal.TelegramClient.Tap;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guardrail 2 end to end inside the api: an application is sent only when the linked user taps
 * Approve, exactly once, within the daily limit, and with a CV that belongs to that user and
 * posting. Gmail, S3 and Telegram are mocks; everything else is real.
 */
class ApplyFlowTests extends PostgresIntegrationTest {

    private static final long CHAT = 4242L;
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    @Autowired
    DecisionHandler decisions;

    @Autowired
    FollowUpService followUps;

    @Autowired
    JdbcTemplate jdbc;

    long userId;
    long postingId;
    long cvId;

    @BeforeEach
    void seed() {
        reset(telegram, gmail, cvReader);
        when(cvReader.read(anyString())).thenReturn(PDF);
        jdbc.update("TRUNCATE app_user, posting, company CASCADE");
        userId = user("applicant@example.com", CHAT, 5);
        jdbc.update("UPDATE app_user SET gmail_token_ref = 'jobradar/gmail/1' WHERE id = ?", userId);
        jdbc.update("INSERT INTO profile (user_id, cv_static) VALUES (?, '{\"name\":\"Jane Doe\"}'::jsonb)", userId);
        postingId = posting("Backend Intern", "email", "mailto:hr@acme.example?subject=x");
        cvId = cv(userId, postingId);
    }

    private long user(String email, long chat, int cap) {
        return jdbc.queryForObject(
                "INSERT INTO app_user (email, telegram_chat_id, daily_apply_cap) VALUES (?, ?, ?) RETURNING id",
                Long.class, email, chat, cap);
    }

    private long posting(String title, String channel, String target) {
        Long company = jdbc.queryForObject(
                "INSERT INTO company (name) VALUES ('Acme') ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                Long.class);
        return jdbc.queryForObject(
                """
                INSERT INTO posting (company_id, source, source_id, title, location, apply_channel, apply_target, first_seen_at, content_hash)
                VALUES (?, 'fixture', ?, ?, 'Cairo', ?, ?, now(), ?) RETURNING id
                """,
                Long.class, company, title + channel, title, channel, target, title + channel);
    }

    private long cv(long forUser, long forPosting) {
        return jdbc.queryForObject(
                "INSERT INTO cv_version (user_id, posting_id, bullet_ids, pdf_s3_key, ats_score) VALUES (?, ?, '{1}', 'cv/x.pdf', 50) RETURNING id",
                Long.class, forUser, forPosting);
    }

    private static Tap tap(long from, long chat, String data) {
        return new Tap(1, "cb", from, chat, 99, data);
    }

    private Map<String, Object> application() {
        return jdbc.queryForMap("SELECT status, channel, sent_at, follow_up_at FROM application WHERE posting_id = ?", postingId);
    }

    private void notified() {
        jdbc.update("INSERT INTO notification (user_id, type, posting_id, sent_at) VALUES (?, 'apply_now', ?, now())", userId, postingId);
    }

    // ---------------------------------------------------------------------------------- approve

    @Test
    void approveSendsTheCvOnceAndRecordsTheApplicationWithAFollowUp() {
        notified();

        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));
        // A second tap on the same prompt must not send again.
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        ArgumentCaptor<OutgoingEmail> email = ArgumentCaptor.forClass(OutgoingEmail.class);
        verify(gmail, times(1)).send(eq("jobradar/gmail/1"), email.capture());
        assertThat(email.getValue().to()).isEqualTo("hr@acme.example");
        assertThat(email.getValue().from()).isEqualTo("applicant@example.com");
        assertThat(email.getValue().subject()).isEqualTo("Application: Backend Intern - Jane Doe");
        assertThat(email.getValue().attachment()).isEqualTo(PDF);

        Map<String, Object> row = application();
        assertThat(row).containsEntry("status", "applied").containsEntry("channel", "email");
        Instant sent = ((Timestamp) row.get("sent_at")).toInstant();
        // Follow-up after 8 days, inside the plan's 7 to 10.
        assertThat(Duration.between(sent, ((Timestamp) row.get("follow_up_at")).toInstant())).isEqualTo(Duration.ofDays(8));
        assertThat(jdbc.queryForObject("SELECT action FROM notification WHERE posting_id = ?", String.class, postingId))
                .isEqualTo("approve");
        // Both taps remove the buttons: the second one only learns that the application exists.
        verify(telegram, times(2)).clearButtons(CHAT, 99);
        verify(telegram).sendMessage(eq(CHAT), contains("Sent to hr@acme.example"), any(), any());
        verify(telegram).sendMessage(eq(CHAT), contains("already applied"), any(), any());
    }

    @Test
    void aTapFromAnyoneButTheLinkedPrivateChatSendsNothing() {
        // Right chat, wrong sender (for example the prompt was forwarded into a group).
        decisions.handle(tap(7, CHAT, "ap:" + postingId + ":" + cvId));
        // A chat that is linked to nobody.
        decisions.handle(tap(555, 555, "ap:" + postingId + ":" + cvId));

        verify(gmail, never()).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
        verify(telegram, times(2)).answerTap(eq("cb"), contains("no longer valid"));
    }

    @Test
    void aStaleOrForgedButtonSendsNothing() {
        decisions.handle(tap(CHAT, CHAT, "ap:not-a-number:1"));
        decisions.handle(tap(CHAT, CHAT, "xx:1:2"));

        verify(gmail, never()).send(any(), any());
    }

    @Test
    void anotherUsersCvOrAnotherPostingsCvIsNotFound() {
        long other = user("other@example.com", 777, 5);
        long othersCv = cv(other, postingId);
        long otherPosting = posting("Data Intern", "email", "hr@acme.example");
        long cvForOtherPosting = cv(userId, otherPosting);

        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + othersCv));
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvForOtherPosting));

        verify(gmail, never()).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
    }

    @Test
    void theDailyLimitStopsFurtherEmails() {
        jdbc.update("UPDATE app_user SET daily_apply_cap = 1 WHERE id = ?", userId);
        long first = posting("Intern A", "email", "a@acme.example");
        long firstCv = cv(userId, first);

        decisions.handle(tap(CHAT, CHAT, "ap:" + first + ":" + firstCv));
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        verify(gmail, times(1)).send(any(), any());
        verify(telegram).sendMessage(eq(CHAT), contains("Daily limit of 1"), any(), any());
        // The refused posting stays open: the buttons were not removed, and nothing was recorded.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application WHERE posting_id = ?", Integer.class, postingId)).isZero();
    }

    @Test
    void aFailedSendRecordsNothingAndCanBeRetried() {
        doThrow(new IllegalStateException("Gmail answered 500")).when(gmail).send(any(), any());

        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
        verify(telegram, never()).clearButtons(anyLong(), anyLong());
        verify(telegram).sendMessage(eq(CHAT), contains("try again"), any(), any());

        reset(gmail);
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        verify(gmail, times(1)).send(any(), any());
        assertThat(application()).containsEntry("status", "applied");
    }

    @Test
    void aDisconnectedGmailSendsNothing() {
        doThrow(new GmailNotConnectedException("revoked")).when(gmail).send(any(), any());

        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
        verify(telegram).sendMessage(eq(CHAT), contains("connected again"), any(), any());
    }

    @Test
    void withoutAGmailTokenNothingIsEvenAttempted() {
        jdbc.update("UPDATE app_user SET gmail_token_ref = NULL WHERE id = ?", userId);

        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));

        verify(gmail, never()).send(any(), any());
        verify(telegram).sendMessage(eq(CHAT), contains("not connected"), any(), any());
    }

    @Test
    void aPostingThatIsNotAnEmailChannelCannotBeApprovedForSending() {
        long form = posting("Form Intern", "ats_form", "https://acme.example/apply");
        long formCv = cv(userId, form);

        decisions.handle(tap(CHAT, CHAT, "ap:" + form + ":" + formCv));

        verify(gmail, never()).send(any(), any());
        verify(telegram).sendMessage(eq(CHAT), contains("no email address"), any(), any());
    }

    // ------------------------------------------------------------------------- applied elsewhere

    @Test
    void iAppliedRecordsAManualApplicationAndSendsNothing() {
        long form = posting("Form Intern", "ats_form", "https://acme.example/apply");
        long formCv = cv(userId, form);

        decisions.handle(tap(CHAT, CHAT, "ma:" + form + ":" + formCv));

        verify(gmail, never()).send(any(), any());
        assertThat(jdbc.queryForMap("SELECT status, channel FROM application WHERE posting_id = ?", form))
                .containsEntry("status", "applied")
                .containsEntry("channel", "manual");
    }

    @Test
    void skipAndLaterOnlyRecordTheChoice() {
        notified();

        decisions.handle(tap(CHAT, CHAT, "sk:" + postingId));
        // The first choice stands.
        decisions.handle(tap(CHAT, CHAT, "lt:" + postingId));

        assertThat(jdbc.queryForObject("SELECT action FROM notification WHERE posting_id = ?", String.class, postingId))
                .isEqualTo("skip");
        verify(gmail, never()).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------------------- follow-ups

    @Test
    void aDueFollowUpIsSentOnceWithADraftAndStatusButtons() {
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));
        // Not due yet: nothing is sent.
        assertThat(followUps.sendDue()).isZero();
        reset(telegram);
        jdbc.update("UPDATE application SET follow_up_at = now() - interval '1 hour' WHERE posting_id = ?", postingId);

        assertThat(followUps.sendDue()).isEqualTo(1);
        assertThat(followUps.sendDue()).isZero();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<TelegramClient.Button>>> rows = ArgumentCaptor.forClass(List.class);
        verify(telegram, times(1)).sendWithButtons(eq(CHAT), text.capture(), rows.capture());
        assertThat(text.getValue()).contains("Follow up: Backend Intern · Acme", "Draft:", "Best regards,\nJane Doe");
        assertThat(rows.getValue().get(0)).extracting(TelegramClient.Button::label).containsExactly("Got a reply", "Rejected");
    }

    @Test
    void aReplyTapUpdatesTheStatusAndAnotherUserCannotChangeIt() {
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));
        long applicationId = jdbc.queryForObject("SELECT id FROM application", Long.class);
        user("other@example.com", 777, 5);

        decisions.handle(tap(777, 777, "fr:" + applicationId));
        assertThat(application()).containsEntry("status", "applied");

        decisions.handle(tap(CHAT, CHAT, "fr:" + applicationId));
        assertThat(application()).containsEntry("status", "replied");
    }

    @Test
    void anApplicationFollowedUpIsNotRemindedAfterAReply() {
        decisions.handle(tap(CHAT, CHAT, "ap:" + postingId + ":" + cvId));
        long applicationId = jdbc.queryForObject("SELECT id FROM application", Long.class);
        decisions.handle(tap(CHAT, CHAT, "fr:" + applicationId));
        jdbc.update("UPDATE application SET follow_up_at = now() - interval '1 hour'");
        reset(telegram);

        assertThat(followUps.sendDue()).isZero();
        verify(telegram, never()).sendWithButtons(anyLong(), any(), any());
    }
}
