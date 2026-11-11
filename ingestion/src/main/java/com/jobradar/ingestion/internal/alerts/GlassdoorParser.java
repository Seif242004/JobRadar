package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glassdoor alert emails. Job links carry {@code jobListingId=ID} or end in {@code JV_...KO...,...htm?jl=ID}. Only the email is read: the job page is never requested.
 * Unverified against a real email (see fixtures/README.md); a link this does not recognise is
 * skipped, so a wrong guess costs nothing but a missed posting.
 */
final class GlassdoorParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:www\\.)?glassdoor\\.[a-z.]+/[^\\s\"<>]*?(?:jobListingId=|[?&]jl=)(\\d{6,})", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "glassdoor";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String id = matcher.group(1);
        return Optional.of(new JobRef(id, "https://www.glassdoor.com/job-listing/j?jl=" + id));
    }
}
