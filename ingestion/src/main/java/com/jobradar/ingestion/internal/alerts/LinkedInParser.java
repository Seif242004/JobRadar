package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkedIn job alert emails. Only the email is read: no LinkedIn page is ever requested, and the
 * posting is applied to by the user on LinkedIn itself (apply channel {@code platform}).
 * Job links are {@code linkedin.com/comm/jobs/view/{id}}, {@code linkedin.com/jobs/view/{id}} or
 * a jobs page carrying {@code currentJobId={id}}; all become {@code https://www.linkedin.com/jobs/view/{id}}.
 * Checked against a real email (see fixtures/README.md).
 */
final class LinkedInParser implements BoardParser {

    private static final Pattern VIEW = Pattern.compile(
            "https?://(?:[a-z]{2,3}\\.)?linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?#\\s]*?-)?(\\d{6,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT_JOB = Pattern.compile(
            "https?://(?:[a-z]{2,3}\\.)?linkedin\\.com/(?:comm/)?jobs/[^\\s?#]*\\?[^\\s#]*?currentJobId=(\\d{6,})", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "linkedin";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = VIEW.matcher(url);
        if (!matcher.find()) {
            matcher = CURRENT_JOB.matcher(url);
            if (!matcher.find()) {
                return Optional.empty();
            }
        }
        String id = matcher.group(1);
        return Optional.of(new JobRef(id, "https://www.linkedin.com/jobs/view/" + id));
    }
}
