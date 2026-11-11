package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic emails: they pin the link shapes the extra parsers accept, not what the boards really send. */
class MoreBoardsAlertTests {

    private final AlertParsers parsers = new AlertParsers();

    private List<RawPosting> parse(String html) {
        return parsers.parse(new AlertEmail("m", Instant.parse("2026-11-11T10:00:00Z"), html, "")).postings();
    }

    @Test
    void indeedViewJobAndClickLinksYieldThePostingByJobKey() {
        List<RawPosting> postings = parse("""
                <a href="https://eg.indeed.com/viewjob?jk=a1b2c3d4e5f60718&from=alert">Junior Backend Developer</a>
                <a href="https://eg.indeed.com/rc/clk?jk=0f1e2d3c4b5a6978&fccid=1">Software Intern</a>""");

        assertThat(postings).extracting(RawPosting::title).containsExactlyInAnyOrder("Junior Backend Developer", "Software Intern");
        assertThat(postings).allSatisfy(p -> assertThat(p.applyTarget()).startsWith("https://www.indeed.com/viewjob?jk="));
    }

    @Test
    void glassdoorLinksWithAListingIdAreRead() {
        List<RawPosting> postings = parse(
                "<a href=\"https://www.glassdoor.com/partner/jobListing.htm?jobListingId=1009876543&ao=1\">Graduate Engineer</a>");

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).title()).isEqualTo("Graduate Engineer");
    }

    @Test
    void forasnaJobLinksAreRead() {
        List<RawPosting> postings = parse("<a href=\"https://forasna.com/jobs/software-engineer-cairo-123456\">Software Engineer</a>");

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).applyTarget()).isEqualTo("https://forasna.com/jobs/software-engineer-cairo-123456");
    }

    @Test
    void companyNewsletterLinksToAtsPostingsAreRead() {
        List<RawPosting> postings = parse("""
                <a href="https://boards.greenhouse.io/acme/jobs/4455667">Graduate Program 2027</a>
                <a href="https://jobs.lever.co/acme/8f7e6d5c-1234-4abc-9def-0123456789ab">Backend Intern</a>""");

        assertThat(postings).extracting(RawPosting::title).containsExactlyInAnyOrder("Graduate Program 2027", "Backend Intern");
    }

    @Test
    void anOrdinaryLinkIsNotAJob() {
        assertThat(parse("<a href=\"https://example.com/blog/post\">Read more</a>")).isEmpty();
    }
}
