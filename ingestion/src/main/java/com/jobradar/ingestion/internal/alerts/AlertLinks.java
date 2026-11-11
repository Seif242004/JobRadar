package com.jobradar.ingestion.internal.alerts;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link handling for alert emails: reading anchors out of HTML and unwrapping click-tracking
 * links. Unwrapping only decodes the target that the tracking URL already carries in its query
 * string. No tracking link is ever requested, so nothing is sent to a job board or tracker.
 */
final class AlertLinks {

    record Link(String href, String text) {}

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+", Pattern.CASE_INSENSITIVE);

    /** Query parameters that tracking links use to carry the real destination. */
    private static final Set<String> TARGET_PARAMS = Set.of("url", "u", "redirect", "redirect_url", "target", "link", "dest", "destination");

    /** A tracker that wraps a tracker is followed this deep and no further. */
    private static final int MAX_UNWRAP = 3;

    private AlertLinks() {}

    /** Every anchor with an http(s) href, in document order. The text is the anchor's inner text, markup removed. */
    static List<Link> anchors(String html) {
        List<Link> links = new ArrayList<>();
        Matcher matcher = ANCHOR.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            href = com.jobradar.ingestion.internal.source.SourceText.unescape(href).strip();
            if (href.regionMatches(true, 0, "http", 0, 4)) {
                String text = com.jobradar.ingestion.internal.source.SourceText.plain(matcher.group(3));
                links.add(new Link(href, text == null ? "" : text.replaceAll("\\s+", " ")));
            }
        }
        return links;
    }

    /** Every http(s) URL in running text, trailing punctuation removed. */
    static List<String> urls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group().replaceAll("[.,;:!?]+$", ""));
        }
        return urls;
    }

    /**
     * The URL itself, plus the destination it carries when it is a tracking wrapper, plus that
     * destination's own, up to {@value #MAX_UNWRAP} levels. Outermost first. Parsers try each
     * candidate, so a direct board link and a wrapped one are handled alike.
     */
    static List<String> candidates(String url) {
        List<String> candidates = new ArrayList<>();
        String current = url;
        for (int depth = 0; depth <= MAX_UNWRAP && current != null; depth++) {
            candidates.add(current);
            current = embeddedTarget(current);
        }
        return candidates;
    }

    private static String embeddedTarget(String url) {
        String query;
        try {
            query = new URI(url).getRawQuery();
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            int q = url.indexOf('?');
            query = q < 0 ? null : url.substring(q + 1);
        }
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !TARGET_PARAMS.contains(pair.substring(0, eq).toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                String decoded = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).strip();
                if (decoded.regionMatches(true, 0, "http", 0, 4)) {
                    return decoded;
                }
            } catch (IllegalArgumentException e) {
                // Malformed percent-encoding: not a usable target.
            }
        }
        return null;
    }
}
