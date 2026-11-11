package com.jobradar.ingestion.internal.alerts;

import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Properties;

/**
 * Turns the RFC 822 bytes of a message into an {@link AlertEmail}: the first text/html and the
 * first text/plain part, found by walking multipart containers and attached or forwarded
 * messages. Malformed parts are skipped rather than failing the message.
 */
final class MailParser {

    /** An alert is a few hundred KB at most; anything bigger is not read further. */
    private static final int MAX_BODY_CHARS = 2_000_000;
    private static final int MAX_DEPTH = 8;
    private static final Session SESSION = Session.getInstance(new Properties());

    private MailParser() {}

    static AlertEmail parse(String messageId, byte[] rfc822, Instant receivedAt) {
        Bodies bodies = new Bodies();
        try {
            collect(new MimeMessage(SESSION, new ByteArrayInputStream(rfc822)), bodies, 0);
        } catch (MessagingException | RuntimeException e) {
            // A message that cannot be parsed yields no postings; the caller counts it as empty.
        }
        return new AlertEmail(messageId, receivedAt, bodies.html, bodies.text);
    }

    private static final class Bodies {
        String html = "";
        String text = "";
    }

    private static void collect(Part part, Bodies bodies, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        try {
            if (part.isMimeType("multipart/*")) {
                jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    collect(multipart.getBodyPart(i), bodies, depth + 1);
                }
            } else if (part.isMimeType("message/rfc822")) {
                collect((Part) part.getContent(), bodies, depth + 1);
            } else if (part.isMimeType("text/html") && bodies.html.isEmpty()) {
                bodies.html = limit(part.getContent());
            } else if (part.isMimeType("text/plain") && bodies.text.isEmpty()) {
                bodies.text = limit(part.getContent());
            }
        } catch (MessagingException | IOException | RuntimeException e) {
            // Skip this part: an unknown charset or a broken encoding must not lose the others.
        }
    }

    private static String limit(Object content) {
        String text = content instanceof String string ? string : "";
        return text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text;
    }
}
