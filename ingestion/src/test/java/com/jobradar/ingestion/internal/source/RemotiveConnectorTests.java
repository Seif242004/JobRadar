package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemotiveConnectorTests {

    private static final String FEED = "https://remotive.com/api/remote-jobs";

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp().fixture(FEED, "remotive/remote-jobs.json");
    private final RemotiveConnector connector = new RemotiveConnector(Fixtures.jsonFetch(http));

    @Test
    void mapsTheRecordedFeed() {
        List<RawPosting> postings = connector.fetch();

        assertThat(postings).hasSize(13);
        RawPosting backend = postings.stream()
                .filter(p -> p.sourceId().equals("2091135"))
                .findFirst()
                .orElseThrow();
        assertThat(backend.source()).isEqualTo("remotive");
        assertThat(backend.companyName()).isEqualTo("Lemon.io");
        assertThat(backend.title()).isEqualTo("Senior Back-end Developer");
        assertThat(backend.location())
                .isEqualTo("Europe, USA, Canada, Australia, Singapore, Mexico, Saudi Arabia");
        assertThat(backend.remotePolicy()).isEqualTo("remote");
        assertThat(backend.description()).isNotBlank().doesNotContain("<p", "</p>");
        // publication_date 2026-10-27T12:56:13, no offset: read as UTC
        assertThat(backend.postedAt()).isEqualTo(Instant.parse("2026-10-27T12:56:13Z"));
    }

    @Test
    void linksBackToRemotiveAsItsTermsRequire() {
        assertThat(connector.fetch()).allSatisfy(posting -> {
            assertThat(posting.source()).isEqualTo("remotive");
            assertThat(posting.applyChannel()).isEqualTo("platform");
            assertThat(posting.applyTarget()).startsWith("https://remotive.com/remote-jobs/");
        });
    }

    @Test
    void oneRequestPerRunAndAtMostFourRunsADay() {
        connector.fetch();

        assertThat(http.requested).containsExactly(FEED);
        assertThat(connector.schedule().maxRunsPerDay()).isEqualTo(4);
        assertThat(connector.schedule().minInterval()).isEqualTo(Duration.ofHours(6));
    }
}
