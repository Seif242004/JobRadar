package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeverConnectorTests {

    private static final String BOSTA = "https://api.lever.co/v0/postings/Bosta?mode=json";
    private static final String DEMO = "https://api.lever.co/v0/postings/leverdemo?mode=json";
    private static final String GONE = "https://api.lever.co/v0/postings/gone?mode=json";

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp()
            .fixture(BOSTA, "lever/bosta.json")
            .fixture(DEMO, "lever/leverdemo.json")
            .on(GONE, new FetchResponse(404, Fixtures.read("lever/unknown-board-404.json"), null));

    private LeverConnector connector(Board... boards) {
        return new LeverConnector(atsType -> atsType.equals("lever") ? List.of(boards) : List.of(), Fixtures.jsonFetch(http));
    }

    @Test
    void mapsARecordedEgyptianBoard() {
        // The board token keeps its case: "bosta" answers 404 on Lever.
        List<RawPosting> postings = connector(new Board("Bosta", "Bosta")).fetch();

        assertThat(postings).hasSize(3);
        RawPosting dataEngineer = postings.stream()
                .filter(p -> p.title().equals("Senior Data Engineer"))
                .findFirst()
                .orElseThrow();
        assertThat(dataEngineer.source()).isEqualTo("lever");
        assertThat(dataEngineer.sourceId()).isEqualTo("002239d4-d595-468f-8a2b-de9b6f3ff549");
        assertThat(dataEngineer.companyName()).isEqualTo("Bosta");
        assertThat(dataEngineer.location()).isEqualTo("Cairo");
        assertThat(dataEngineer.remotePolicy()).isEqualTo("hybrid");
        assertThat(dataEngineer.language()).isNull();
        assertThat(dataEngineer.applyChannel()).isEqualTo("ats_form");
        assertThat(dataEngineer.applyTarget())
                .isEqualTo("https://jobs.lever.co/Bosta/002239d4-d595-468f-8a2b-de9b6f3ff549/apply");
        assertThat(dataEngineer.deadline()).isNull();
        // createdAt 1779712377363 (epoch milliseconds)
        assertThat(dataEngineer.postedAt()).isEqualTo(Instant.ofEpochMilli(1779712377363L));
    }

    @Test
    void descriptionJoinsTheIntroTheListsAndTheClosingText() {
        RawPosting dataEngineer = connector(new Board("Bosta", "Bosta")).fetch().stream()
                .filter(p -> p.title().equals("Senior Data Engineer"))
                .findFirst()
                .orElseThrow();

        assertThat(dataEngineer.description())
                .startsWith("About Bosta:")
                .contains("Job Responsibilities", "Job Qualifications", "Thank you for considering Bosta")
                .doesNotContain("<li>", "</li>");
    }

    @Test
    void unspecifiedWorkplaceTypeBecomesNull() {
        List<RawPosting> postings = connector(new Board("Lever Demo", "leverdemo")).fetch();

        assertThat(postings).extracting(RawPosting::title, RawPosting::remotePolicy)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Customer Success Manager", "hybrid"),
                        org.assertj.core.groups.Tuple.tuple("Senior Software Developer, Platform", "remote"),
                        org.assertj.core.groups.Tuple.tuple("Stephanie Test Posting A", null));
    }

    @Test
    void aBoardThatNoLongerExistsIsSkippedAndTheOthersStillRun() {
        List<RawPosting> postings = connector(new Board("Gone", "gone"), new Board("Bosta", "Bosta")).fetch();

        assertThat(postings).hasSize(3);
        assertThat(http.requested).containsExactly(GONE, BOSTA);
    }
}
