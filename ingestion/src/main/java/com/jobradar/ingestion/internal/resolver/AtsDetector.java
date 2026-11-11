package com.jobradar.ingestion.internal.resolver;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the ATS behind a careers page by the board links and embeds every ATS leaves in it.
 * Works on any text: a careers URL on its own, or the HTML of the page.
 */
public final class AtsDetector {

    /** First path segments of ATS hosts that are not board tokens. */
    private static final Set<String> NOT_A_TOKEN = Set.of("api", "embed", "j", "js", "v1", "www", "apply", "jobs", "help");

    private record Rule(String atsType, Pattern pattern, Function<MatchResult, String> token) {}

    /** Checked in order; the first match wins. Token group 1 unless stated. */
    private static final List<Rule> RULES = List.of(
            // boards.greenhouse.io/embed/job_board?for=acme and .../embed/job_board/js?for=acme
            new Rule("greenhouse", Pattern.compile("greenhouse\\.io/embed/job_board(?:/js)?\\?for=([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // boards.greenhouse.io/acme, job-boards.greenhouse.io/acme, job-boards.eu.greenhouse.io/acme
            new Rule("greenhouse", Pattern.compile("(?:job-)?boards(?:\\.eu)?\\.greenhouse\\.io/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // boards-api.greenhouse.io/v1/boards/acme/jobs
            new Rule("greenhouse", Pattern.compile("boards-api\\.greenhouse\\.io/v1/boards/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // jobs.lever.co/Acme (case-sensitive token), jobs.eu.lever.co/acme, api.lever.co/v0/postings/acme
            // jobs.ashbyhq.com/acme and api.ashbyhq.com/posting-api/job-board/acme
            new Rule("ashby", Pattern.compile("jobs\\.ashbyhq\\.com/([A-Za-z0-9_.-]+)"), m -> m.group(1)),
            new Rule("ashby", Pattern.compile("api\\.ashbyhq\\.com/posting-api/job-board/([A-Za-z0-9_.-]+)"), m -> m.group(1)),
            new Rule("lever", Pattern.compile("jobs(?:\\.eu)?\\.lever\\.co/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            new Rule("lever", Pattern.compile("api(?:\\.eu)?\\.lever\\.co/v0/postings/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // apply.workable.com/acme, apply.workable.com/api/v1/widget/accounts/acme
            new Rule("workable", Pattern.compile("apply\\.workable\\.com/api/v1/widget/accounts/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            new Rule("workable", Pattern.compile("apply\\.workable\\.com/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // acme.workable.com (older boards)
            new Rule("workable", Pattern.compile("(?<![A-Za-z0-9.-])([a-z0-9-]+)\\.workable\\.com"), m -> m.group(1)),
            // jobs.smartrecruiters.com/Acme, careers.smartrecruiters.com/Acme
            new Rule("smartrecruiters", Pattern.compile("(?:jobs|careers)\\.smartrecruiters\\.com/([A-Za-z0-9_-]+)"), m -> m.group(1)),
            // acme.wd5.myworkdayjobs.com/en-US/External: Workday needs host and site, so both are kept
            new Rule(
                    "workday",
                    Pattern.compile("([a-z0-9-]+\\.wd\\d+\\.myworkdayjobs\\.com)/(?:[a-z]{2}-[A-Z]{2}/)?([A-Za-z0-9_-]+)"),
                    m -> m.group(1) + "/" + m.group(2)));

    private AtsDetector() {}

    /** The first ATS board referenced in {@code text}; empty when there is none. */
    public static Optional<AtsBoard> detect(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                if (!NOT_A_TOKEN.contains(matcher.group(1))) {
                    return Optional.of(new AtsBoard(rule.atsType(), rule.token().apply(matcher)));
                }
            }
        }
        return Optional.empty();
    }
}
