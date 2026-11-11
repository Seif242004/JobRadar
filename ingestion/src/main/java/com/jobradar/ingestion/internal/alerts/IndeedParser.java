package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indeed alert emails. Job links carry the job key as {@code jk}: {@code https://eg.indeed.com/viewjob?jk=abc123} or {@code /rc/clk?jk=abc123}. Only the email is read: the job page is never requested.
 * Unverified against a real email (see fixtures/README.md); a link this does not recognise is
 * skipped, so a wrong guess costs nothing but a missed posting.
 */
final class IndeedParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:[a-z]{2,3}\\.)?indeed\\.com/(?:m/)?(?:viewjob|rc/clk|pagead/clk)\\?[^\\s\"<>]*?\\bjk=([0-9a-f]{8,32})", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "indeed";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String id = matcher.group(1);
        return Optional.of(new JobRef(id, "https://www.indeed.com/viewjob?jk=" + id));
    }
}
