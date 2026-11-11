package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forasna alert emails. Job links look like {@code https://forasna.com/jobs/{slug}-{id}} or {@code /en/jobs/{id}}. Only the email is read: the job page is never requested.
 * Unverified against a real email (see fixtures/README.md); a link this does not recognise is
 * skipped, so a wrong guess costs nothing but a missed posting.
 */
final class ForasnaParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:www\\.)?forasna\\.com/(?:[a-z]{2}/)?(?:jobs?|vacancy)/(?:[^/?#\\s]*?-)?(\\d{4,})", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "forasna";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String id = matcher.group(1);
        return Optional.of(new JobRef(id, matcher.group().replaceAll("[?#].*$", "")));
    }
}
