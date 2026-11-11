package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertLinksTests {

    @Test
    void anchorsAreReadWithDecodedHrefAndPlainText() {
        var links = AlertLinks.anchors("<p><a class=x href=\"https://a.example/x?p=1&amp;q=2\">Hello <b>World</b></a> <A HREF='http://b.example'>B</A> <a href=\"mailto:x@y.z\">m</a></p>");

        assertThat(links).extracting(AlertLinks.Link::href).containsExactly("https://a.example/x?p=1&q=2", "http://b.example");
        assertThat(links).extracting(AlertLinks.Link::text).containsExactly("Hello World", "B");
    }

    @Test
    void trackingWrappersAreUnwrappedFromTheirOwnQueryStringOnly() {
        String wrapped = "https://t.example/c?id=9&u=https%3A%2F%2Fwuzzuf.net%2Fjobs%2Fp%2Fabc1-Job";

        assertThat(AlertLinks.candidates(wrapped)).containsExactly(wrapped, "https://wuzzuf.net/jobs/p/abc1-Job");
    }

    @Test
    void nestedWrappersAreFollowedAFewLevelsAndNoFurther() {
        String level3 = "https://deep.example/end";
        String url = level3;
        for (int i = 0; i < 6; i++) {
            url = "https://t" + i + ".example/c?url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(AlertLinks.candidates(url)).hasSize(4);
    }

    @Test
    void malformedQueryStringsAreIgnored() {
        assertThat(AlertLinks.candidates("https://t.example/c?u=%ZZ&url=notaurl")).hasSize(1);
        assertThat(AlertLinks.candidates("https://t.example/c")).hasSize(1);
    }

    @Test
    void urlsInRunningTextLoseTrailingPunctuation() {
        assertThat(AlertLinks.urls("See https://a.example/x, or (https://b.example/y).")).containsExactly("https://a.example/x", "https://b.example/y");
    }
}
