package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceSchedule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreenhouseConnectorTests {

    private static final String GITLAB = "https://boards-api.greenhouse.io/v1/boards/gitlab/jobs?content=true";
    private static final String CANONICAL = "https://boards-api.greenhouse.io/v1/boards/canonical/jobs?content=true";
    private static final String GONE = "https://boards-api.greenhouse.io/v1/boards/gone/jobs?content=true";

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp()
            .fixture(GITLAB, "greenhouse/gitlab.json")
            .fixture(CANONICAL, "greenhouse/canonical.json")
            .on(GONE, new FetchResponse(404, Fixtures.read("greenhouse/unknown-board-404.json"), null));

    private GreenhouseConnector connector(Board... boards) {
        return new GreenhouseConnector(atsType -> atsType.equals("greenhouse") ? List.of(boards) : List.of(), Fixtures.jsonFetch(http));
    }

    @Test
    void mapsARecordedBoard() {
        List<RawPosting> postings = connector(new Board("GitLab", "gitlab")).fetch();

        assertThat(postings).hasSize(3);
        RawPosting backend = postings.get(2);
        assertThat(backend.source()).isEqualTo("greenhouse");
        assertThat(backend.sourceId()).isEqualTo("gitlab:8771512002");
        assertThat(backend.companyName()).isEqualTo("GitLab");
        assertThat(backend.title()).isEqualTo("Backend Engineer, GitLab Orbit");
        assertThat(backend.location()).isEqualTo("Remote, Canada; Remote, United States");
        assertThat(backend.remotePolicy()).isEqualTo("remote");
        assertThat(backend.language()).isEqualTo("en");
        assertThat(backend.applyChannel()).isEqualTo("ats_form");
        assertThat(backend.applyTarget()).isEqualTo("https://job-boards.greenhouse.io/gitlab/jobs/8771512002");
        assertThat(backend.deadline()).isNull();
        // first_published 2026-10-23T18:47:07-04:00
        assertThat(backend.postedAt()).isEqualTo(Instant.parse("2026-10-23T22:47:07Z"));
    }

    @Test
    void descriptionIsPlainTextWithoutMarkupOrEntities() {
        RawPosting posting = connector(new Board("GitLab", "gitlab")).fetch().get(0);

        assertThat(posting.description())
                .startsWith("GitLab is the intelligent orchestration platform for DevSecOps.")
                .doesNotContain("<", "&lt;", "&quot;", "&nbsp;", "&amp;");
    }

    @Test
    void usesTheWatchlistCompanyNameAndLeavesRemotePolicyOpenWhenTheLocationDoesNotSayRemote() {
        RawPosting posting = connector(new Board("Canonical Ltd", "canonical")).fetch().stream()
                .filter(p -> p.sourceId().equals("canonical:4124053"))
                .findFirst()
                .orElseThrow();

        assertThat(posting.companyName()).isEqualTo("Canonical Ltd");
        assertThat(posting.location()).isEqualTo("Home based - EMEA");
        assertThat(posting.remotePolicy()).isNull();
        assertThat(posting.postedAt()).isEqualTo(Instant.parse("2022-05-16T13:23:22Z"));
    }

    @Test
    void aBoardThatNoLongerExistsIsSkippedAndTheOthersStillRun() {
        List<RawPosting> postings = connector(new Board("Gone", "gone"), new Board("GitLab", "gitlab")).fetch();

        assertThat(postings).hasSize(3).allSatisfy(p -> assertThat(p.sourceId()).startsWith("gitlab:"));
        assertThat(http.requested).containsExactly(GONE, GITLAB);
    }

    @Test
    void makesOneRequestPerBoardAndPollsEvery15Minutes() {
        GreenhouseConnector connector = connector(new Board("GitLab", "gitlab"), new Board("Canonical", "canonical"));

        assertThat(connector.fetch()).hasSize(6);
        assertThat(http.requested).containsExactly(GITLAB, CANONICAL);
        assertThat(connector.schedule()).isEqualTo(SourceSchedule.ATS);
    }

    @Test
    void aRateLimitStopsTheRun() {
        http.on(GITLAB, new FetchResponse(429, "", java.time.Duration.ofSeconds(120)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> connector(new Board("GitLab", "gitlab"), new Board("Canonical", "canonical")).fetch())
                .isInstanceOfSatisfying(SourceRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).hasSeconds(120));
        assertThat(http.requested).containsExactly(GITLAB);
    }
}
