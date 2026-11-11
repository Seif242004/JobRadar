package com.jobradar.ingestion.internal.enrich;

import com.jobradar.ingestion.internal.source.SourceText;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the posting text out of a public job page. The page's own schema.org JobPosting data
 * (JSON-LD) is preferred, because that is what the employer publishes for search engines; failing
 * that, the visible text of the page minus scripts, styles and navigation.
 */
final class PageText {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LD_JSON = Pattern.compile(
            "(?is)<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>");
    private static final Pattern NOISE = Pattern.compile(
            "(?is)<(script|style|noscript|nav|header|footer|form|svg|iframe)\\b.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern MAIN = Pattern.compile("(?is)<(main|article)\\b[^>]*>(.*?)</\\1>");

    private PageText() {}

    /** The best text found, or null when the page holds nothing usable. */
    static String extract(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String structured = fromJsonLd(html);
        if (structured != null) {
            return structured;
        }
        String cleaned = COMMENT.matcher(NOISE.matcher(html).replaceAll(" ")).replaceAll(" ");
        Matcher main = MAIN.matcher(cleaned);
        String body = main.find() ? main.group(2) : cleaned;
        return SourceText.plain(body);
    }

    private static String fromJsonLd(String html) {
        Matcher matcher = LD_JSON.matcher(html);
        while (matcher.find()) {
            try {
                String description = description(JSON.readTree(matcher.group(1).strip()));
                if (description != null) {
                    return description;
                }
            } catch (RuntimeException e) {
                // Malformed JSON-LD is common; try the next block.
            }
        }
        return null;
    }

    /** Finds a JobPosting anywhere in the JSON (single object, array or @graph) and returns its text. */
    private static String description(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                String found = description(item);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        JsonNode type = node.path("@type");
        boolean job = type.isString() ? type.asString().equals("JobPosting")
                : type.isArray() && type.toString().contains("JobPosting");
        if (job) {
            String html = SourceText.text(node, "description");
            if (html == null) {
                return null;
            }
            // The field holds HTML, sometimes escaped twice.
            return SourceText.plain(SourceText.unescape(html).contains("<") ? SourceText.unescape(html) : html);
        }
        return description(node.path("@graph"));
    }
}
