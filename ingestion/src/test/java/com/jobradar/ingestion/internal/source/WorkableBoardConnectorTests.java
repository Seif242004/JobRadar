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

/** Fixtures are real widget responses recorded on 2026-11-11 (see fixtures/README.md). */
class WorkableBoardConnectorTests {

    private static final String BOARD = "https://apply.workable.com/api/v1/widget/accounts/foodics?details=true";
    private static final String QUIET = "https://apply.workable.com/api/v1/widget/accounts/moneyfellows?details=true";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp()
            .fixture(BOARD, "workable/board-foodics.json")
            .fixture(QUIET, "workable/board-moneyfellows-empty.json");

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
        List<RawPosting> postings = connector(new Board("Foodics", "foodics")).fetch();

        // 4 entries: the Senior Sales Executive posting is listed once per city.
        assertThat(postings).hasSize(3);
        RawPosting support = byTitle(postings, "Customer Support Specialist");
        assertThat(support.source()).isEqualTo("workable-board");
        assertThat(support.sourceId()).isEqualTo("foodics:78090B0EF3");
        assertThat(support.companyName()).isEqualTo("Foodics");
        assertThat(support.location()).isEqualTo("Cairo, Egypt");
        assertThat(support.remotePolicy()).isNull();
        assertThat(support.applyChannel()).isEqualTo("ats_form");
        assertThat(support.applyTarget()).isEqualTo("https://apply.workable.com/j/78090B0EF3");
        assertThat(support.description()).contains("We Are Foodics!").doesNotContain("<p>", "<strong>");
        // published_on is a date before today: start of that day is the safe bound.
        assertThat(support.postedAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void foldsAPostingListedPerCityIntoOne() {
        RawPosting sales = byTitle(connector(new Board("Foodics", "foodics")).fetch(), "Senior Sales Executive");

        assertThat(sales.location()).isEqualTo("Dubai, United Arab Emirates; Abu Dhabi, United Arab Emirates");
        assertThat(sales.sourceId()).isEqualTo("foodics:1A5DCA6E6E");
    }

    @Test
    void telecommutingMeansRemote() {
        RawPosting remote = byTitle(connector(new Board("Foodics", "foodics")).fetch(),
                "Junior AI Forward Engineer, Foodics Online");

        assertThat(remote.remotePolicy()).isEqualTo("remote");
        assertThat(remote.location()).isEqualTo("Amman, Jordan");
    }

    @Test
    void aPostingDatedTodayHasNoPostedAtSoItIsFreshOnFirstSighting() {
        Clock onPublishDay = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC);
        WorkableBoardConnector connector = new WorkableBoardConnector(
                atsType -> List.of(new Board("Foodics", "foodics")), Fixtures.jsonFetch(http), onPublishDay);

        assertThat(byTitle(connector.fetch(), "Customer Support Specialist").postedAt()).isNull();
    }

    @Test
    void anEmptyBoardYieldsNothing() {
        assertThat(connector(new Board("MoneyFellows", "moneyfellows")).fetch()).isEmpty();
    }

    @Test
    void readsOnlyBoardsResolvedAsWorkableAndMakesOneRequestPerBoard() {
        connector(new Board("Foodics", "foodics"), new Board("MoneyFellows", "moneyfellows")).fetch();

        assertThat(http.requested).containsExactly(BOARD, QUIET);
    }

    @Test
    void aRateLimitStopsTheRunInsteadOfHammeringTheHost() {
        http.on(BOARD, new FetchResponse(429, "error code: 1015", Duration.ofSeconds(23351)));

        assertThatThrownBy(() -> connector(new Board("Foodics", "foodics"), new Board("MoneyFellows", "moneyfellows")).fetch())
                .isInstanceOfSatisfying(SourceRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).hasSeconds(23351));
        assertThat(http.requested).containsExactly(BOARD);
    }

    @Test
    void aBoardThatDoesNotExistIsSkipped() {
        http.on(BOARD, new FetchResponse(404, "", null));

        assertThat(connector(new Board("Foodics", "foodics"), new Board("MoneyFellows", "moneyfellows")).fetch()).isEmpty();
        assertThat(http.requested).containsExactly(BOARD, QUIET);
    }
}
