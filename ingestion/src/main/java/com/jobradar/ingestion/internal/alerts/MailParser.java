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
                bodies.html = limit(text(part));
            } else if (part.isMimeType("text/plain") && bodies.text.isEmpty()) {
                bodies.text = limit(text(part));
            }
        } catch (MessagingException | IOException | RuntimeException e) {
            // Skip this part: an unknown charset or a broken encoding must not lose the others.
        }
    }

    /**
     * Decodes a text part. Alert mail often omits the charset on its parts, and JavaMail then reads UTF-8
     * bytes as Latin-1 ("â" in place of a dash), so an undeclared charset is treated as UTF-8.
     */
    private static String text(Part part) throws MessagingException, IOException {
        String charset = null;
        try {
            charset = new jakarta.mail.internet.ContentType(part.getContentType()).getParameter("charset");
        } catch (jakarta.mail.internet.ParseException e) {
            // fall through to the default
        }
        java.nio.charset.Charset decode = java.nio.charset.StandardCharsets.UTF_8;
        if (charset != null) {
            try {
                decode = java.nio.charset.Charset.forName(charset.replace("\"", ""));
            } catch (RuntimeException e) {
                // unknown charset: keep UTF-8
            }
        }
        try (java.io.InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), decode);
        }
    }

    private static String limit(String text) {
        return text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text;
    }
}
