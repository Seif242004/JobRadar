package com.jobradar.ingestion.internal.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PageTextTests {

    @Test
    void jobPostingJsonLdIsPreferred() {
        String html = """
                <html><head><script type="application/ld+json">
                {"@context":"https://schema.org","@type":"JobPosting","title":"Backend Intern",
                 "description":"<p>Build <b>Java</b> services with Spring Boot.</p><ul><li>PostgreSQL</li><li>Docker</li></ul>"}
                </script></head><body><nav>Menu</nav><p>Cookie banner</p></body></html>""";

        String text = PageText.extract(html);

        assertThat(text).contains("Build Java services with Spring Boot.", "PostgreSQL", "Docker").doesNotContain("Cookie", "<p>");
    }

    @Test
    void aGraphWrapperIsSearched() {
        String html = """
                <script type="application/ld+json">{"@graph":[{"@type":"Organization","name":"x"},
                {"@type":"JobPosting","description":"Python and SQL role"}]}</script>""";

        assertThat(PageText.extract(html)).isEqualTo("Python and SQL role");
    }

    @Test
    void withoutStructuredDataTheMainTextIsUsedAndNoiseDropped() {
        String html = "<html><head><style>.a{}</style></head><body><header>Site</header>"
                + "<main><h1>Graduate Engineer</h1><p>Work with Kubernetes and Go.</p></main>"
                + "<footer>Legal</footer><script>var x=1;</script></body></html>";

        String text = PageText.extract(html);

        assertThat(text).contains("Graduate Engineer", "Kubernetes and Go.").doesNotContain("Site", "Legal", "var x");
    }

    @Test
    void jobBoardsAreNeverFetched() {
        assertThat(DescriptionEnricher.blocked(URI.create("https://www.linkedin.com/jobs/view/1"))).isTrue();
        assertThat(DescriptionEnricher.blocked(URI.create("https://wuzzuf.net/jobs/p/abc"))).isTrue();
        assertThat(DescriptionEnricher.blocked(URI.create("https://jobs.lever.co/acme/1"))).isFalse();
    }
}
