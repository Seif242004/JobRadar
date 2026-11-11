package com.jobradar.ingestion.internal.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplyMethodDetectorTests {

    @Test
    void findsGoogleFormAndPrefersItOverEmail() {
        var m = ApplyMethodDetector.detect("Apply here https://forms.gle/AbC123 or send your CV to hr@acme.com");
        assertThat(m).get().extracting("channel", "target").containsExactly("google_form", "https://forms.gle/AbC123");
    }

    @Test
    void findsDocsFormsLink() {
        var m = ApplyMethodDetector.detect("Fill https://docs.google.com/forms/d/e/1FAIpQLS/viewform to apply");
        assertThat(m).get().extracting("channel").isEqualTo("google_form");
    }

    @Test
    void findsEmailNextToApplicationCue() {
        var m = ApplyMethodDetector.detect("Interested? Send your CV to careers@acme.com with the subject Backend.");
        assertThat(m).get().extracting("channel", "target").containsExactly("email", "careers@acme.com");
    }

    @Test
    void ignoresNoReplyAndAddressesWithoutCue() {
        assertThat(ApplyMethodDetector.detect("Please apply on our site. Do not write to noreply@acme.com")).isEmpty();
        assertThat(ApplyMethodDetector.detect("Office: 12 Nile St. ".repeat(10) + "owner@acme.com " + "Lorem ipsum ".repeat(20)))
                .isEmpty();
    }

    @Test
    void nothingForBlankText() {
        assertThat(ApplyMethodDetector.detect(null)).isEmpty();
        assertThat(ApplyMethodDetector.detect("  ")).isEmpty();
    }
}
