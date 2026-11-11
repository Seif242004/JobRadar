package com.jobradar.ingestion.internal.enrich;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads how to apply from a posting's text: a Google Form link or a contact e-mail address.
 *
 * <p>Conservative on purpose. A Google Form wins over an e-mail address. An address counts only
 * when an application cue ("send your CV", "apply", "email", "submit") sits close to it, and
 * no-reply, support and privacy mailboxes never count. Nothing is sent from here; the result
 * only sets the channel that the user's approval later acts on.
 */
public final class ApplyMethodDetector {

    /** The detected channel ({@code google_form} or {@code email}) and its target. */
    public record Method(String channel, String target) {}

    private static final Pattern FORM = Pattern.compile(
            "https?://(?:docs\\.google\\.com/forms/[^\\s<>\"')\\]]+|forms\\.gle/[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern CUE = Pattern.compile(
            "send|email|e-mail|mail|apply|application|submit|forward|cv|resume|contact|reach", Pattern.CASE_INSENSITIVE);
    private static final Pattern IGNORED_LOCAL = Pattern.compile(
            "(?:no-?reply|do-?not-?reply|support|privacy|abuse|unsubscribe|postmaster|webmaster)", Pattern.CASE_INSENSITIVE);
    /** Characters before and after an address that may hold the application cue. */
    private static final int WINDOW = 80;

    private ApplyMethodDetector() {}

    public static Optional<Method> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher form = FORM.matcher(text);
        if (form.find()) {
            return Optional.of(new Method("google_form", form.group()));
        }
        Matcher email = EMAIL.matcher(text);
        while (email.find()) {
            String address = email.group();
            String local = address.substring(0, address.indexOf('@'));
            if (IGNORED_LOCAL.matcher(local).find() || address.toLowerCase(Locale.ROOT).endsWith(".png")) {
                continue;
            }
            int from = Math.max(0, email.start() - WINDOW);
            int to = Math.min(text.length(), email.end() + WINDOW);
            String around = text.substring(from, email.start()) + " " + text.substring(email.end(), to);
            if (CUE.matcher(around).find()) {
                return Optional.of(new Method("email", address));
            }
        }
        return Optional.empty();
    }
}
