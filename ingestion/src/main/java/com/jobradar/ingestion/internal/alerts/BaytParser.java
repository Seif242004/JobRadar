package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bayt alert emails. Only the email is read: the job page is never requested.
 * Job links look like {@code https://www.bayt.com/en/egypt/jobs/{slug}-{id}/}. Link text is
 * read as "Title - Company - Location" when it has that shape. Unverified against a real email
 * (see fixtures/README.md).
 */
final class BaytParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:www\\.)?bayt\\.com/(?:[a-z]{2}/)?[a-z-]+/jobs/([^/?#\\s]+?)-(\\d{5,})/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREE_PARTS = Pattern.compile("^(.+?)\\s+[-\u2013\u2014]\\s+(.+?)\\s+[-\u2013\u2014]\\s+(.+)$");

    @Override
    public String board() {
        return "bayt";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        // Query string and fragment (tracking) are dropped; the matched prefix is the stable URL.
        String canonical = matcher.group().replaceAll("/+$", "") + "/";
        return Optional.of(new JobRef(matcher.group(2), canonical));
    }

    @Override
    public Optional<Parts> split(String linkText) {
        Matcher matcher = THREE_PARTS.matcher(linkText);
        return matcher.matches()
                ? Optional.of(new Parts(matcher.group(1).strip(), matcher.group(2).strip(), matcher.group(3).strip()))
                : Optional.empty();
    }
}
