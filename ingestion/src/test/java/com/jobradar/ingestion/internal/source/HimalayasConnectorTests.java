package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

class HimalayasConnectorTests {

    @Test
    void mapsTheRecordedSearchAndKeepsTheTermsRules() {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().fixture(HimalayasConnector.pageUrl(1), "himalayas/search-egypt.json");
        HimalayasConnector connector = new HimalayasConnector(Fixtures.jsonFetch(http), "test-key");

        List<RawPosting> postings = connector.fetch();

        assertThat(postings).hasSize(5);
        assertThat(postings).allSatisfy(p -> {
            assertThat(p.source()).isEqualTo("himalayas");
            // The application link is passed on unchanged, and the source is named in the text.
            assertThat(p.applyTarget()).startsWith("https://himalayas.app/").contains("utm_source=api");
            assertThat(p.description()).contains("Source: Himalayas, https://himalayas.app/");
            assertThat(p.remotePolicy()).isEqualTo("remote");
            assertThat(p.location()).isEqualTo("Worldwide");
            assertThat(p.postedAt()).isNotNull();
        });
        assertThat(postings.get(0).title()).startsWith("STEM Expert");
    }

    @Test
    void withoutAKeyNothingIsRequested() {
        Fixtures.FakeHttp http = new Fixtures.FakeHttp();

        assertThat(new HimalayasConnector(Fixtures.jsonFetch(http), "").fetch()).isEmpty();
        assertThat(http.requested).isEmpty();
    }
}
