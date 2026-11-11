package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The emails here are synthetic (fixtures/README.md), so these tests pin how documented and
 * plausible alert shapes are read, not what the boards really send.
 */
class AlertParsersTests {

    private final AlertParsers parsers = new AlertParsers();

    private static RawPosting byId(List<RawPosting> postings, String sourceId) {
        return postings.stream().filter(p -> p.sourceId().equals(sourceId)).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------- LinkedIn

    @Test
    void linkedInAlertYieldsOnePostingPerJobWithCanonicalUrls() {
        AlertParsers.Parsed parsed = parsers.parse(Emails.load("linkedin-alert.eml"));

        assertThat(parsed.postings()).hasSize(3);
        RawPosting backend = byId(parsed.postings(), "linkedin:4012345678");
        assertThat(backend.source()).isEqualTo("gmail");
        assertThat(backend.title()).isEqualTo("Senior Backend Engineer");
        assertThat(backend.companyName()).isEqualTo("Paymob");
        assertThat(backend.location()).isEqualTo("Cairo, Egypt");
        // Tracking parameters are gone; the user applies on LinkedIn, never the system.
        assertThat(backend.applyTarget()).isEqualTo("https://www.linkedin.com/jobs/view/4012345678");
        assertThat(backend.applyChannel()).isEqualTo("platform");
        assertThat(backend.postedAt()).isEqualTo(Emails.RECEIVED);
    }

    @Test
    void linkedInCompanyAndLocationOnOneLineAreSplit_andCurrentJobIdLinksAreUnderstood() {
        List<RawPosting> postings = parsers.parse(Emails.load("linkedin-alert.eml")).postings();

        RawPosting java = byId(postings, "linkedin:4012399999");
        assertThat(java.companyName()).isEqualTo("Sumerge");
        assertThat(java.location()).isEqualTo("Cairo, Egypt (Hybrid)");
        RawPosting intern = byId(postings, "linkedin:4012300001");
        assertThat(intern.title()).isEqualTo("Software Engineer Intern");
        assertThat(intern.companyName()).isEqualTo("Instabug");
        assertThat(intern.applyTarget()).isEqualTo("https://www.linkedin.com/jobs/view/4012300001");
    }

    @Test
    void linkedInPlainTextAloneIsEnoughAndFootersAreNotJobs() {
        AlertEmail textOnly = new AlertEmail("m", Emails.RECEIVED, "", Emails.load("linkedin-alert.eml").text());

        List<RawPosting> postings = parsers.parse(textOnly).postings();

        assertThat(postings).extracting(RawPosting::title)
                .containsExactlyInAnyOrder("Senior Backend Engineer", "Java Developer", "Software Engineer Intern");
        assertThat(postings).allSatisfy(p -> assertThat(p.sourceId()).startsWith("linkedin:"));
    }

    // ---------------------------------------------------------------- Wuzzuf

    @Test
    void wuzzufAlertReadsTitleCompanyAndLocation() {
        List<RawPosting> postings = parsers.parse(Emails.load("wuzzuf-alert.eml")).postings();

        assertThat(postings).hasSize(3);
        RawPosting backend = byId(postings, "wuzzuf:aB3dE9xYz1");
        assertThat(backend.title()).isEqualTo("Senior Backend Engineer");
        assertThat(backend.companyName()).isEqualTo("Fawry");
        assertThat(backend.location()).isEqualTo("Cairo, Egypt");
        // utm parameters are not part of the stable URL; the slug is kept.
        assertThat(backend.applyTarget()).isEqualTo("https://wuzzuf.net/jobs/p/aB3dE9xYz1-Senior-Backend-Engineer-Fawry-Cairo-Egypt");
        assertThat(backend.applyChannel()).isEqualTo("platform");
    }

    @Test
    void wuzzufTrackerWrappedLinksAreUnwrappedWithoutBeingFollowed_andArabicSurvives() {
        RawPosting wrapped = byId(parsers.parse(Emails.load("wuzzuf-alert.eml")).postings(), "wuzzuf:q7Zr2LmN0p");

        assertThat(wrapped.applyTarget()).startsWith("https://wuzzuf.net/jobs/p/q7Zr2LmN0p-").doesNotContain("click.wuzzuf.net", "utm_");
        assertThat(wrapped.title()).isEqualTo("Junior Java Developer");
        assertThat(wrapped.companyName()).isEqualTo("جيزة للنظم");
        assertThat(wrapped.location()).isEqualTo("Giza, Egypt");
    }

    @Test
    void wuzzufItemWithOnlyACompanyLineStillGivesAPosting() {
        RawPosting data = byId(parsers.parse(Emails.load("wuzzuf-alert.eml")).postings(), "wuzzuf:k1Lm8Pq4Tz");

        assertThat(data.title()).isEqualTo("Data Engineer");
        assertThat(data.companyName()).isEqualTo("MNT-Halan");
        assertThat(data.location()).isNull();
    }

    // ------------------------------------------------------------------ Bayt

    @Test
    void baytLinkTextIsSplitIntoTitleCompanyAndLocation() {
        List<RawPosting> postings = parsers.parse(Emails.load("bayt-alert.eml")).postings();

        assertThat(postings).hasSize(3);
        RawPosting java = byId(postings, "bayt:5412345");
        assertThat(java.title()).isEqualTo("Senior Java Developer");
        assertThat(java.companyName()).isEqualTo("Sumerge");
        assertThat(java.location()).isEqualTo("Cairo, Egypt");
        assertThat(java.applyTarget()).isEqualTo("https://www.bayt.com/en/egypt/jobs/senior-java-developer-5412345/");
    }

    @Test
    void baytTrackerLinkIsUnwrapped_andTitleOnlyItemsAreKept() {
        List<RawPosting> postings = parsers.parse(Emails.load("bayt-alert.eml")).postings();

        RawPosting backend = byId(postings, "bayt:5498765");
        assertThat(backend.applyTarget()).isEqualTo("https://www.bayt.com/en/egypt/jobs/backend-engineer-5498765/");
        assertThat(backend.companyName()).isEqualTo("Vodafone");
        RawPosting qa = byId(postings, "bayt:5455555");
        assertThat(qa.title()).isEqualTo("QA Engineer");
        assertThat(qa.companyName()).isNull();
    }

    // -------------------------------------------------------------- tolerance

    @Test
    void anEmailWithNoJobLinksYieldsNothingAndDoesNotThrow() {
        AlertParsers.Parsed parsed = parsers.parse(Emails.load("newsletter-no-jobs.eml"));

        assertThat(parsed.postings()).isEmpty();
    }

    @Test
    void garbageInputYieldsNothingAndDoesNotThrow() {
        assertThat(parsers.parse(new AlertEmail("m", Emails.RECEIVED, "<a href=\"https://wuzzuf.net/jobs/p/", "\u0000\u0001 https://")).postings()).isEmpty();
        assertThat(parsers.parse(new AlertEmail("m", Emails.RECEIVED, null, null)).postings()).isEmpty();
        assertThat(MailParser.parse("m", "not an email at all".getBytes(), Emails.RECEIVED).html()).isEmpty();
        assertThat(MailParser.parse("m", new byte[0], Emails.RECEIVED).text()).isEmpty();
    }

    @Test
    void aJobLinkWithoutAnyTitleIsSkippedAndCounted() {
        String html = "<a href=\"https://wuzzuf.net/jobs/p/zz11-Some-Job\">View job</a>";

        AlertParsers.Parsed parsed = parsers.parse(new AlertEmail("m", Emails.RECEIVED, html, ""));

        assertThat(parsed.postings()).isEmpty();
        assertThat(parsed.skipped()).isEqualTo(1);
    }

    @Test
    void theSameJobInTheHtmlAndTheTextPartIsOnePosting() {
        AlertEmail email = Emails.load("linkedin-alert.eml");

        assertThat(email.html()).isNotBlank();
        assertThat(email.text()).isNotBlank();
        assertThat(parsers.parse(email).postings()).extracting(RawPosting::sourceId).doesNotHaveDuplicates();
    }

    @Test
    void aLinkOfAnotherBoardIsNotMistakenForAJob() {
        String html = "<a href=\"https://example.com/jobs/p/abc123-Fake\">Fake Job</a>"
                + "<a href=\"https://example.com/?next=https%3A%2F%2Fevil.example%2Fwuzzuf.net%2Fjobs%2Fp%2Fabc\">x</a>";

        assertThat(parsers.parse(new AlertEmail("m", Emails.RECEIVED, html, "")).postings()).isEmpty();
    }

    // ------------------------------------------- real emails (recorded from a mailbox)

    @Test
    void realLinkedInAlertIsReadFromItsPlainTextBlocks() {
        List<RawPosting> postings = parsers.parse(Emails.load("linkedin-real.eml")).postings();

        assertThat(postings).hasSize(4);
        RawPosting first = byId(postings, "linkedin:4489479446");
        assertThat(first.title()).isEqualTo("Jr. Data Engineer");
        assertThat(first.companyName()).isEqualTo("Mona Trading LLC");
        assertThat(first.location()).isEqualTo("Cairo, Egypt");
        assertThat(first.applyTarget()).isEqualTo("https://www.linkedin.com/jobs/view/4489479446");
        assertThat(first.applyChannel()).isEqualTo("platform");
        RawPosting django = byId(postings, "linkedin:4487451704");
        assertThat(django.title()).isEqualTo("Arabic-speaking Django developer");
        assertThat(django.companyName()).isEqualTo("AlifBee");
        assertThat(django.location()).isEqualTo("Egypt");
        // Two posts with the same title are two different jobs.
        assertThat(byId(postings, "linkedin:4489186059").location()).isEqualTo("Cairo");
        assertThat(byId(postings, "linkedin:4489175017").location()).isEqualTo("Egypt");
    }

    @Test
    void realWuzzufAlertUnwrapsThePathEncodedAwsTracker() {
        List<RawPosting> postings = parsers.parse(Emails.load("wuzzuf-real.eml")).postings();

        assertThat(postings).hasSize(1);
        RawPosting odoo = byId(postings, "wuzzuf:ek1fr7iZI8ja");
        assertThat(odoo.title()).isEqualTo("Odoo Implementation Specialist");
        assertThat(odoo.companyName()).isEqualTo("Confidential");
        assertThat(odoo.location()).isEqualTo("Cairo, Egypt");
        assertThat(odoo.applyTarget()).isEqualTo("https://wuzzuf.net/jobs/p/ek1fr7iZI8ja-Odoo-Implementation-Specialist-Cairo-Egypt");
    }
}
