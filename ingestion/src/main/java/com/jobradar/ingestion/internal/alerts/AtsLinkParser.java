package com.jobradar.ingestion.internal.alerts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Company career newsletters and alerts that link straight to an ATS posting: Greenhouse, Lever, Workable, Ashby, SmartRecruiters, Recruitee, BambooHR, Teamtailor, Comeet. Only the email is read: the job page is never requested.
 * Unverified against a real email (see fixtures/README.md); a link this does not recognise is
 * skipped, so a wrong guess costs nothing but a missed posting.
 */
final class AtsLinkParser implements BoardParser {

    private static final Pattern JOB = Pattern.compile(
            "https?://(?:boards\\.greenhouse\\.io|job-boards\\.greenhouse\\.io|jobs\\.lever\\.co|apply\\.workable\\.com|jobs\\.ashbyhq\\.com|jobs\\.smartrecruiters\\.com|[a-z0-9-]+\\.recruitee\\.com|[a-z0-9-]+\\.bamboohr\\.com|[a-z0-9-]+\\.teamtailor\\.com|www\\.comeet\\.com)/[^\\s\"<>?#]*[A-Za-z0-9]{4,}", Pattern.CASE_INSENSITIVE);

    @Override
    public String board() {
        return "ats";
    }

    @Override
    public Optional<JobRef> job(String url) {
        Matcher matcher = JOB.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String link = matcher.group().replaceAll("/+$", "");
        // The host plus path identifies the posting; a bare company page ends in the company name.
        return Optional.of(new JobRef(link.replaceFirst("^https?://", ""), link));
    }
}
