package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkableSearchConnectorTests {

    private static final String EGYPT = "https://jobs.workable.com/api/v1/jobs?location=Egypt";

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp().fixture(EGYPT, "workable/search-egypt.json");
    private final WorkableSearchConnector connector =
            new WorkableSearchConnector(Fixtures.jsonFetch(http), List.of("Egypt"));

    @Test
    void mapsTheRecordedEgyptSearch() {
        List<RawPosting> postings = connector.fetch();

        assertThat(postings).hasSize(6);
        RawPosting backend = postings.stream()
                .filter(p -> p.sourceId().equals("3d07e14c-a528-4686-b015-97f57eefb84f"))
                .findFirst()
                .orElseThrow();
        assertThat(backend.source()).isEqualTo("workable");
        assertThat(backend.companyName()).isEqualTo("Foodics");
        assertThat(backend.title()).isEqualTo("Senior Software Engineer PAY (Back-End)");
        assertThat(backend.location()).isEqualTo("Cairo, Egypt");
        assertThat(backend.remotePolicy()).isEqualTo("remote");
        assertThat(backend.language()).isEqualTo("en");
        assertThat(backend.applyChannel()).isEqualTo("ats_form");
        assertThat(backend.applyTarget())
                .isEqualTo("https://jobs.workable.com/view/8x7fzQEsUfPyyeiSWmYKqk/"
                        + "remote-senior-software-engineer-pay-(back-end)-in-cairo-at-foodics");
        assertThat(backend.postedAt()).isEqualTo(Instant.parse("2026-11-10T11:01:20.785Z"));
        assertThat(backend.description()).isNotBlank().doesNotContain("<p>", "</p>");
    }

    @Test
    void mapsWorkplaceValuesAndACountryOnlyLocation() {
        List<RawPosting> postings = connector.fetch();

        assertThat(postings).extracting(RawPosting::companyName, RawPosting::location, RawPosting::remotePolicy)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("Nawy Real Estate", "New Cairo City, Egypt", "onsite"),
                        org.assertj.core.groups.Tuple.tuple("Advansys", "Nasr City, Egypt", "hybrid"),
                        // city is "" in the response
                        org.assertj.core.groups.Tuple.tuple("BlackStone eIT", "Egypt", "remote"));
    }

    @Test
    void makesOneRequestPerLocationAndPollsEvery15Minutes() {
        connector.fetch();

        assertThat(http.requested).containsExactly(EGYPT);
        assertThat(connector.schedule()).isEqualTo(SourceSchedule.ATS);
    }

    @Test
    void theRecordedRateLimitAnswerBecomesABackOffNotARetry() {
        http.on(EGYPT, new FetchResponse(429, Fixtures.read("workable/rate-limit-429.json"), Duration.ofSeconds(80921)));

        assertThatThrownBy(connector::fetch)
                .isInstanceOfSatisfying(SourceRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).hasSeconds(80921));
        assertThat(http.requested).hasSize(1);
    }
}
