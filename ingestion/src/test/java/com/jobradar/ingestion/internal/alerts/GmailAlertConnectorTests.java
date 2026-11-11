package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.internal.source.SourceRateLimitedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GmailAlertConnectorTests {

    private static final GmailAlertProperties PROPERTIES = new GmailAlertProperties(
            true, "JobRadar", 3, 50, "client-id", "client-secret", "http://unused", "http://unused");

    /** In-memory Gmail: per refresh token, a label and the messages in it. Records every call. */
    private static final class FakeGmail implements GmailApi {
        final Map<String, String> labelByToken = new java.util.HashMap<>();
        final Map<String, List<String>> idsByLabel = new java.util.HashMap<>();
        final Map<String, byte[]> messages = new java.util.HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();
        RuntimeException failOnList;

        @Override
        public String accessToken(String refreshToken) {
            calls.add("token");
            if (revoked.contains(refreshToken)) {
                throw new GmailAccessException("Google rejected the refresh token");
            }
            return "access-for-" + refreshToken;
        }

        @Override
        public Optional<String> labelId(String accessToken, String labelName) {
            calls.add("label:" + labelName);
            return Optional.ofNullable(labelByToken.get(accessToken.substring("access-for-".length())));
        }

        @Override
        public List<String> messageIds(String accessToken, String labelId, int newerThanDays, int max) {
            calls.add("list:" + labelId + ":" + newerThanDays + ":" + max);
            if (failOnList != null) {
                throw failOnList;
            }
            return idsByLabel.getOrDefault(labelId, List.of());
        }

        @Override
        public RawMessage message(String accessToken, String messageId) {
            calls.add("get:" + messageId);
            return new RawMessage(messages.get(messageId), Emails.RECEIVED);
        }
    }

    private final FakeGmail gmail = new FakeGmail();
    private final List<AlertUserRepository.AlertUser> userList = new ArrayList<>();

    private GmailAlertConnector connector(GmailAlertProperties properties) {
        AlertUserRepository users = new AlertUserRepository(null) {
            @Override
            List<AlertUser> usersWithGmail() {
                return userList;
            }
        };
        return new GmailAlertConnector(users, ref -> "refresh-" + ref, gmail, new AlertParsers(), properties);
    }

    private void givenUserWithMail(long id, String label, String... emails) {
        userList.add(new AlertUserRepository.AlertUser(id, "ref" + id));
        gmail.labelByToken.put("refresh-ref" + id, label);
        List<String> ids = new ArrayList<>();
        for (String email : emails) {
            String messageId = id + "-" + email;
            ids.add(messageId);
            gmail.messages.put(messageId, Emails.bytes(email));
        }
        gmail.idsByLabel.put(label, ids);
    }

    @Test
    void readsOnlyTheConfiguredLabelAndTurnsItsAlertsIntoPostings() {
        givenUserWithMail(1, "Label_7", "linkedin-alert.eml", "wuzzuf-alert.eml", "bayt-alert.eml");

        List<RawPosting> postings = connector(PROPERTIES).fetch();

        assertThat(postings).hasSize(9);
        assertThat(postings).allSatisfy(p -> {
            assertThat(p.source()).isEqualTo("gmail");
            assertThat(p.applyChannel()).isEqualTo("platform");
        });
        // The only calls: token, label lookup by the configured name, a list filtered by that label, and one get per message.
        assertThat(gmail.calls).containsExactly(
                "token", "label:JobRadar", "list:Label_7:3:50",
                "get:1-linkedin-alert.eml", "get:1-wuzzuf-alert.eml", "get:1-bayt-alert.eml");
    }

    @Test
    void aMessageIsReadOnceNotOnEveryPoll() {
        givenUserWithMail(1, "Label_7", "linkedin-alert.eml");
        GmailAlertConnector connector = connector(PROPERTIES);

        assertThat(connector.fetch()).hasSize(3);
        gmail.calls.clear();
        assertThat(connector.fetch()).isEmpty();

        assertThat(gmail.calls).noneMatch(call -> call.startsWith("get:"));
    }

    @Test
    void aMessageWithNoRecognisedJobsIsStillRememberedSoItIsNotFetchedForever() {
        givenUserWithMail(1, "Label_7", "newsletter-no-jobs.eml");
        GmailAlertConnector connector = connector(PROPERTIES);

        assertThat(connector.fetch()).isEmpty();
        gmail.calls.clear();
        connector.fetch();

        assertThat(gmail.calls).noneMatch(call -> call.startsWith("get:"));
    }

    @Test
    void aUserWhoseAccessWasRevokedOrWhoHasNoLabelIsSkippedAndOthersStillRun() {
        givenUserWithMail(1, "Label_1", "bayt-alert.eml");
        givenUserWithMail(2, "Label_2", "wuzzuf-alert.eml");
        userList.add(new AlertUserRepository.AlertUser(3, "ref3")); // no label of that name
        gmail.revoked.add("refresh-ref1");

        List<RawPosting> postings = connector(PROPERTIES).fetch();

        assertThat(postings).hasSize(3).allSatisfy(p -> assertThat(p.sourceId()).startsWith("wuzzuf:"));
    }

    @Test
    void anUnexpectedFailureForOneUserDoesNotLoseTheOthers() {
        givenUserWithMail(1, "Label_1", "bayt-alert.eml");
        gmail.failOnList = new IllegalStateException("Google answered HTTP 500");
        userList.add(new AlertUserRepository.AlertUser(2, "ref2"));
        gmail.labelByToken.put("refresh-ref2", "Label_2");

        assertThat(connector(PROPERTIES).fetch()).isEmpty(); // both failed or empty, nothing thrown
    }

    @Test
    void aRateLimitFromGoogleStopsTheRun() {
        givenUserWithMail(1, "Label_1", "bayt-alert.eml");
        gmail.failOnList = new SourceRateLimitedException("gmail", Duration.ofMinutes(10));

        assertThatThrownBy(() -> connector(PROPERTIES).fetch()).isInstanceOf(SourceRateLimitedException.class);
    }

    @Test
    void doesNothingWithoutConnectedUsersOrWithoutAnOAuthClient() {
        assertThat(connector(PROPERTIES).fetch()).isEmpty();
        assertThat(gmail.calls).isEmpty();

        givenUserWithMail(1, "Label_1", "bayt-alert.eml");
        GmailAlertProperties noClient = new GmailAlertProperties(true, "JobRadar", 3, 50, "", "", "http://unused", "http://unused");
        assertThat(connector(noClient).fetch()).isEmpty();
        assertThat(gmail.calls).isEmpty();
    }

    @Test
    void pollsEveryFiveMinutes() {
        assertThat(connector(PROPERTIES).schedule().minInterval()).isEqualTo(Duration.ofMinutes(5));
    }
}
