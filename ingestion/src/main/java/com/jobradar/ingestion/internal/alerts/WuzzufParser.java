package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wuzzuf alert emails. Only the email is read: the job page is never requested.
 * Job links look like {@code https://wuzzuf.net/jobs/p/{id}-{title-company-city-country}}.
 * Checked against a real email (see fixtures/README.md).
 */
final class WuzzufParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:www\\.)?wuzzuf\\.net/jobs/p/([A-Za-z0-9]+)(-[^/?#\\s]*)?", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "wuzzuf";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String slug = matcher.group(2) == null ? "" : matcher.group(2);
        return Optional.of(new JobRef(matcher.group(1), "https://wuzzuf.net/jobs/p/" + matcher.group(1) + slug));
    }
}
