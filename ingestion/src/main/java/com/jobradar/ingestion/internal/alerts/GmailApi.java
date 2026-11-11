package com.jobradar.ingestion.internal.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The four Gmail calls the reader makes, and no others. Reading is limited to one label by
 * construction: there is no call that lists or fetches a message outside a label.
 */
interface GmailApi {

    /** One message as Gmail stores it. */
    record RawMessage(byte[] rfc822, Instant receivedAt) {}

    /** @throws GmailAccessException when Google rejects the refresh token (revoked or expired) */
    String accessToken(String refreshToken);

    /** The ID of the label with this name, or empty when the user has no such label. */
    Optional<String> labelId(String accessToken, String labelName);

    /** IDs of the messages in the label that arrived within the last {@code newerThanDays} days, newest first. */
    List<String> messageIds(String accessToken, String labelId, int newerThanDays, int max);

    RawMessage message(String accessToken, String messageId);
}
