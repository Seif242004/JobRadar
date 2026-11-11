package com.jobradar.ingestion.internal.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AtsDetectorTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://boards.greenhouse.io/acme                                         | greenhouse      | acme
            <a href="https://job-boards.greenhouse.io/gitlab/jobs/8771512002">Apply</a> | greenhouse      | gitlab
            https://job-boards.eu.greenhouse.io/acme                                  | greenhouse      | acme
            <script src="https://boards.greenhouse.io/embed/job_board/js?for=acme">    | greenhouse      | acme
            https://boards-api.greenhouse.io/v1/boards/acme/jobs                      | greenhouse      | acme
            https://jobs.ashbyhq.com/thndr                                             | ashby          | thndr
            https://jobs.lever.co/Bosta                                               | lever           | Bosta
            <a href="https://jobs.lever.co/Bosta/002239d4/apply">                      | lever           | Bosta
            https://apply.workable.com/moneyfellows/                                  | workable        | moneyfellows
            https://apply.workable.com/api/v1/widget/accounts/acme                    | workable        | acme
            <a href="https://acme.workable.com/jobs/1">                                | workable        | acme
            https://jobs.smartrecruiters.com/Acme/743999                              | smartrecruiters | Acme
            https://careers.smartrecruiters.com/Acme                                  | smartrecruiters | Acme
            https://acme.wd1.myworkdayjobs.com/en-US/External/job/Cairo/1             | workday         | acme.wd1.myworkdayjobs.com/External
            https://acme.wd5.myworkdayjobs.com/Careers                                | workday         | acme.wd5.myworkdayjobs.com/Careers
            """)
    void findsTheBoardInUrlsAndPageMarkup(String text, String atsType, String token) {
        assertThat(AtsDetector.detect(text)).contains(new AtsBoard(atsType, token));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<html><body><h1>Careers</h1><a href=\"/careers/backend-engineer\">Backend Engineer</a></body></html>",
        // Links to the ATS vendors themselves are not boards.
        "<a href=\"https://www.workable.com\">Powered by Workable</a>",
        "<a href=\"https://jobs.workable.com/search\">More jobs</a>",
        "https://www.greenhouse.io/",
        ""
    })
    void aPageWithNoBoardLinkHasNoAts(String text) {
        assertThat(AtsDetector.detect(text)).isEmpty();
    }
}
