package com.jobradar.ingestion.internal.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Company and location fields of alert emails: entities, the location glued to the company, social proof. */
class AlertFieldCleaningTests {

    @Test
    void aCompanyWithAnEntityEncodedLocationIsSplit() {
        assertThat(AlertExtractor.splitCompany("Vodafone &amp;middot; Giza (Hybrid)"))
                .containsExactly("Vodafone", "Giza (Hybrid)");
        assertThat(AlertExtractor.splitCompany("Siemens · Cairo (On-site)"))
                .containsExactly("Siemens", "Cairo (On-site)");
        assertThat(AlertExtractor.splitCompany("Instabug")).containsExactly("Instabug", null);
        assertThat(AlertExtractor.splitCompany(null)).containsExactly(null, null);
    }

    @Test
    void socialProofLinesAreNotLocations() {
        assertThat(AlertExtractor.cleanLocation("1 connection")).isNull();
        assertThat(AlertExtractor.cleanLocation("2 connections")).isNull();
        assertThat(AlertExtractor.cleanLocation("8 company alumni")).isNull();
        assertThat(AlertExtractor.cleanLocation("Cairo, Egypt")).isEqualTo("Cairo, Egypt");
        assertThat(AlertExtractor.cleanLocation("Cairo (On-site)")).isEqualTo("Cairo (On-site)");
    }
}
