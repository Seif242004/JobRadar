package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.ingestion.RawPosting;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fixtures here are synthetic (see fixtures/README.md): these tests pin the mapping, not the live API. */
class WorkableBoardConnectorTests {

    private static final String BOARD = "https://apply.workable.com/api/v1/widget/accounts/examplecairo?details=true";
    private static final String QUIET = "https://apply.workable.com/api/v1/widget/accounts/quiet?details=true";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp()
            .fixture(BOARD, "workable/board-synthetic.json")
            .fixture(QUIET, "workable/board-empty-synthetic.json");

    private WorkableBoardConnector connector(Board... boards) {
        // Boards are looked up by ats_type "workable", not by the connector's own source name.
        return new WorkableBoardConnector(
                atsType -> atsType.equals("workable") ? List.of(boards) : List.of(), Fixtures.jsonFetch(http), NOW);
    }

    private static RawPosting byTitle(List<RawPosting> postings, String title) {
        return postings.stream().filter(p -> p.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void mapsABoard() {
        List<RawPosting> postings = connector(new Board("Example Cairo Co", "examplecairo")).fetch();

        // 5 entries: one has no shortcode and is skipped, two are one posting in two places.
        assertThat(postings).hasSize(3);
        RawPosting backend = byTitle(postings, "Backend Engineer (Java)");
        assertThat(backend.source()).isEqualTo("workable-board");
        assertThat(backend.sourceId()).isEqualTo("examplecairo:AB12CD34EF");
        assertThat(backend.companyName()).isEqualTo("Example Cairo Co");
        assertThat(backend.location()).isEqualTo("Cairo, Egypt");
        assertThat(backend.remotePolicy()).isNull();
        assertThat(backend.applyChannel()).isEqualTo("ats_form");
        assertThat(backend.applyTarget()).isEqualTo("https://apply.workable.com/j/AB12CD34EF");
        assertThat(backend.description()).startsWith("Build payment services in Java & Spring Boot.")
                .doesNotContain("<", "&amp;");
        // published_on is a date before today: start of that day is the safe bound.
        assertThat(backend.postedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void foldsAPostingOpenInSeveralCountriesIntoOne() {
        RawPosting solutions = byTitle(connector(new Board("Example Cairo Co", "examplecairo")).fetch(), "Solutions Engineer");

        assertThat(solutions.location()).isEqualTo("Cairo, Egypt; Poland");
        assertThat(solutions.remotePolicy()).isEqualTo("remote");
    }

    @Test
    void aPostingDatedTodayHasNoPostedAtSoItIsFreshOnFirstSighting() {
        RawPosting intern = byTitle(connector(new Board("Example Cairo Co", "examplecairo")).fetch(), "Data Analyst Intern");

        assertThat(intern.postedAt()).isNull();
        assertThat(intern.location()).isEqualTo("Giza, Egypt");
    }

    @Test
    void anEmptyBoardYieldsNothing() {
        assertThat(connector(new Board("Quiet Co", "quiet")).fetch()).isEmpty();
    }

    @Test
    void readsOnlyBoardsResolvedAsWorkableAndMakesOneRequestPerBoard() {
        connector(new Board("Example Cairo Co", "examplecairo"), new Board("Quiet Co", "quiet")).fetch();

        assertThat(http.requested).containsExactly(BOARD, QUIET);
    }

    @Test
    void aRateLimitStopsTheRunInsteadOfHammeringTheHost() {
        http.on(BOARD, new FetchResponse(429, "error code: 1015", Duration.ofSeconds(23351)));

        assertThatThrownBy(() -> connector(new Board("Example Cairo Co", "examplecairo"), new Board("Quiet Co", "quiet")).fetch())
                .isInstanceOfSatisfying(SourceRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).hasSeconds(23351));
        assertThat(http.requested).containsExactly(BOARD);
    }

    @Test
    void aBoardThatDoesNotExistIsSkipped() {
        http.on(BOARD, new FetchResponse(404, "", null));

        assertThat(connector(new Board("Example Cairo Co", "examplecairo"), new Board("Quiet Co", "quiet")).fetch()).isEmpty();
        assertThat(http.requested).containsExactly(BOARD, QUIET);
    }
}
