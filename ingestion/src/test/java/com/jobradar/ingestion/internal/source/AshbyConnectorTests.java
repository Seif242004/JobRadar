package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AshbyConnectorTests {

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp()
            .fixture("https://api.ashbyhq.com/posting-api/job-board/thndr", "ashby/thndr.json");

    @Test
    void mapsARecordedEgyptianBoard() {
        AshbyConnector connector = new AshbyConnector(
                atsType -> atsType.equals("ashby") ? List.of(new Board("Thndr", "thndr")) : List.of(),
                Fixtures.jsonFetch(http));

        List<RawPosting> postings = connector.fetch();

        assertThat(postings).hasSize(3);
        RawPosting devops = postings.get(0);
        assertThat(devops.source()).isEqualTo("ashby");
        assertThat(devops.sourceId()).isEqualTo("706a3396-c21e-4803-8648-f6e590e41b08");
        assertThat(devops.companyName()).isEqualTo("Thndr");
        assertThat(devops.title()).isEqualTo("Senior DevOps/Infrastructure Engineer");
        assertThat(devops.location()).isEqualTo("Cairo Office");
        assertThat(devops.remotePolicy()).isEqualTo("hybrid");
        assertThat(devops.applyChannel()).isEqualTo("ats_form");
        assertThat(devops.applyTarget())
                .isEqualTo("https://jobs.ashbyhq.com/thndr/706a3396-c21e-4803-8648-f6e590e41b08/application");
        assertThat(devops.description()).startsWith("ABOUT THE ROLE");
        assertThat(devops.postedAt()).isEqualTo(Instant.parse("2026-11-08T13:59:29.107Z"));
        assertThat(postings.get(2).remotePolicy()).isEqualTo("onsite");
    }
}
