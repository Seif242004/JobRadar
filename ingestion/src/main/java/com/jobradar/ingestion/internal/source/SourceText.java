package com.jobradar.ingestion.internal.source;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Small parsing helpers shared by the connectors. */
final class SourceText {

    private static final Pattern BLOCK_END = Pattern.compile("(?i)<br\\s*/?>|</(p|div|li|ul|ol|h[1-6]|tr)>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});");

    private SourceText() {}

    /** Text of a JSON field, or null when it is missing, JSON null or blank. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * HTML to plain text: block ends become line breaks, tags are dropped, entities decoded and
     * whitespace collapsed. Good enough for matching and embeddings; not a renderer.
     */
    static String plain(String html) {
        if (html == null) {
            return null;
        }
        String text = BLOCK_END.matcher(html).replaceAll("\n");
        text = unescape(TAG.matcher(text).replaceAll(""));
        text = text.replaceAll("[ \\t\\x0B\\f\\r\\u00A0]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return text.isEmpty() ? null : text;
    }

    /** Decodes the entities that occur in posting HTML: the named basics and numeric ones. */
    static String unescape(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        String text = value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        Matcher numeric = NUMERIC_ENTITY.matcher(text);
        text = numeric.replaceAll(match -> {
            String code = match.group(1);
            int codePoint = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            return Character.isValidCodePoint(codePoint)
                    ? Matcher.quoteReplacement(Character.toString(codePoint))
                    : "";
        });
        // Last, so "&amp;lt;" ends as the literal text "&lt;" and not as "<".
        return text.replace("&amp;", "&");
    }

    /**
     * Parses the timestamp forms the sources use: ISO-8601 with an offset, without one (read as
     * UTC), or a plain date (start of day, UTC). Anything else is null: a wrong posted_at would
     * put a posting in the wrong freshness band, so an unreadable one is treated as absent.
     */
    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to the next form
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through to the next form
        }
        try {
            return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Joins the non-blank parts with {@code separator}; null when none is left. */
    static String join(String separator, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!out.isEmpty()) {
                    out.append(separator);
                }
                out.append(part.strip());
            }
        }
        return out.isEmpty() ? null : out.toString();
    }
}
