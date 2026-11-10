package com.jobradar.ingestion.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/** Canonical forms used for dedupe: the content hash and the apply target. */
public final class PostingNormalizer {

    private PostingNormalizer() {}

    /**
     * SHA-256 of company, title and location in canonical form, so the same job from two sources
     * hashes identically despite differences in case, punctuation or spacing.
     */
    public static String contentHash(String company, String title, String location) {
        String canonical = canonicalText(company) + "|" + canonicalText(title) + "|" + canonicalText(location);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /**
     * Lower case, Unicode-normalised, with every run of characters that is not a letter or digit
     * collapsed to one space. Letters of any script are kept, so Arabic titles survive.
     */
    static String canonicalText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    /**
     * Canonical apply target. URLs lose their fragment, tracking parameters ({@code utm_*}) and
     * trailing slash, and get a lower-case scheme and host. Anything else (an email address) is
     * trimmed and lower-cased. Blank becomes null.
     */
    public static String applyTarget(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (!isHttpUrl(trimmed)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        try {
            URI uri = new URI(trimmed);
            String query = uri.getRawQuery() == null
                    ? null
                    : Arrays.stream(uri.getRawQuery().split("&"))
                            .filter(param -> !param.toLowerCase(Locale.ROOT).startsWith("utm_"))
                            .collect(Collectors.joining("&"));
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            StringBuilder out = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT))
                    .append("://")
                    .append(uri.getRawAuthority().toLowerCase(Locale.ROOT))
                    .append(path);
            if (query != null && !query.isEmpty()) {
                out.append('?').append(query);
            }
            return out.toString();
        } catch (URISyntaxException | NullPointerException e) {
            // Not parseable: keep it as given, so the posting is still stored.
            return trimmed;
        }
    }

    /** Only URLs identify one job; an HR mailbox is shared by many postings. */
    public static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
