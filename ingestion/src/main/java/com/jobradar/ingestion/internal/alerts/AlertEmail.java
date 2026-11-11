package com.jobradar.ingestion.internal.alerts;

import java.time.Instant;

/**
 * One alert email, reduced to what the parsers read.
 *
 * @param messageId  Gmail message ID; used only to avoid reading the same message twice
 * @param receivedAt when Gmail received it; the alert's postings were visible no later than this
 * @param html       the text/html part, or empty
 * @param text       the text/plain part, or empty
 */
public record AlertEmail(String messageId, Instant receivedAt, String html, String text) {

    public AlertEmail {
        html = html == null ? "" : html;
        text = text == null ? "" : text;
    }
}
