package com.jobradar.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SourceTextTests {

    @Test
    void plainDropsTagsDecodesEntitiesAndKeepsBlockBreaks() {
        assertThat(SourceText.plain("<p>Java &amp; Spring&nbsp;Boot</p><ul><li>3+ years</li><li>SQL</li></ul>"))
                .isEqualTo("Java & Spring Boot\n3+ years\nSQL");
    }

    @Test
    void plainOfNothingIsNull() {
        assertThat(SourceText.plain(null)).isNull();
        assertThat(SourceText.plain("<p> </p>")).isNull();
    }

    @Test
    void unescapeDecodesNumericEntitiesAndAmpersandLast() {
        assertThat(SourceText.unescape("&lt;p&gt;caf&#233; &#x2014; R&amp;D&lt;/p&gt;")).isEqualTo("<p>café — R&D</p>");
        assertThat(SourceText.unescape("&amp;lt;")).isEqualTo("&lt;");
    }

    @Test
    void instantReadsTheFormsTheSourcesUse() {
        assertThat(SourceText.instant("2026-10-23T18:47:07-04:00")).isEqualTo(Instant.parse("2026-10-23T22:47:07Z"));
        assertThat(SourceText.instant("2026-11-10T11:01:20.785Z")).isEqualTo(Instant.parse("2026-11-10T11:01:20.785Z"));
        assertThat(SourceText.instant("2026-10-27T12:56:13")).isEqualTo(Instant.parse("2026-10-27T12:56:13Z"));
        assertThat(SourceText.instant("2026-12-01")).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    @Test
    void anUnreadableTimestampIsTreatedAsAbsent() {
        assertThat(SourceText.instant("yesterday")).isNull();
        assertThat(SourceText.instant(null)).isNull();
    }
}
