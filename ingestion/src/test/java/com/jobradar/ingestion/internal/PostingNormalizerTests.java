package com.jobradar.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostingNormalizerTests {

    @Test
    void contentHashIgnoresCasePunctuationAndSpacing() {
        String a = PostingNormalizer.contentHash("Instabug", "Backend Engineer (Java)", "Cairo, Egypt");
        String b = PostingNormalizer.contentHash(" instabug ", "backend engineer - java", "Cairo  Egypt");

        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void contentHashDiffersWhenTitleCompanyOrLocationDiffers() {
        String base = PostingNormalizer.contentHash("Instabug", "Backend Engineer", "Cairo");

        assertThat(PostingNormalizer.contentHash("Instabug", "Frontend Engineer", "Cairo")).isNotEqualTo(base);
        assertThat(PostingNormalizer.contentHash("Paymob", "Backend Engineer", "Cairo")).isNotEqualTo(base);
        assertThat(PostingNormalizer.contentHash("Instabug", "Backend Engineer", "Remote")).isNotEqualTo(base);
    }

    @Test
    void contentHashKeepsArabicLetters() {
        // If non-Latin letters were stripped, every Arabic title would hash the same.
        assertThat(PostingNormalizer.contentHash("شركة", "مهندس برمجيات", "القاهرة"))
                .isNotEqualTo(PostingNormalizer.contentHash("شركة", "محاسب", "القاهرة"));
    }

    @Test
    void applyUrlLosesTrackingParametersFragmentAndTrailingSlash() {
        assertThat(PostingNormalizer.applyTarget(
                        " HTTPS://Apply.Workable.com/acme/j/ABC123/?utm_source=linkedin&ref=x#apply "))
                .isEqualTo("https://apply.workable.com/acme/j/ABC123?ref=x");
    }

    @Test
    void emailTargetsAreLowerCasedAndBlankBecomesNull() {
        assertThat(PostingNormalizer.applyTarget("HR@Example.com")).isEqualTo("hr@example.com");
        assertThat(PostingNormalizer.applyTarget("  ")).isNull();
        assertThat(PostingNormalizer.applyTarget(null)).isNull();
    }
}
