package com.jobradar.apply.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/** Reads the recipient out of a posting's {@code apply_target}. */
public final class MailTarget {

    // Deliberately strict: one plain address, no display name, no list, no control characters.
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    private MailTarget() {}

    /** Accepts {@code hr@example.com} and {@code mailto:hr@example.com?subject=...}. */
    public static Optional<String> parse(String applyTarget) {
        if (applyTarget == null) {
            return Optional.empty();
        }
        String value = applyTarget.strip();
        if (value.regionMatches(true, 0, "mailto:", 0, 7)) {
            value = value.substring(7);
            int query = value.indexOf('?');
            if (query >= 0) {
                value = value.substring(0, query);
            }
        }
        return ADDRESS.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }
}
