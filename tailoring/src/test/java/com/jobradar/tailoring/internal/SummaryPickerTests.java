package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryPickerTests {

    private static final CvSummary BACKEND = new CvSummary(1, "backend", List.of("backend", "java", "spring"), "Backend text.", false, 0);
    private static final CvSummary AI = new CvSummary(2, "ai", List.of("machine learning", "ai", "pytorch"), "AI text.", false, 1);
    private static final CvSummary GRAPHICS = new CvSummary(3, "graphics", List.of("opengl", "game", "graphics"), "Graphics text.", false, 2);
    private static final List<CvSummary> ALL = List.of(BACKEND, AI, GRAPHICS);

    @Test
    void theVariantWithTheBestKeywordOverlapWins() {
        assertThat(SummaryPicker.pick(ALL, "Game Developer Intern", "Unity and OpenGL rendering").orElseThrow().label()).isEqualTo("graphics");
        assertThat(SummaryPicker.pick(ALL, "Machine Learning Intern", "PyTorch models").orElseThrow().label()).isEqualTo("ai");
        assertThat(SummaryPicker.pick(ALL, "Backend Engineer", "Java and Spring APIs").orElseThrow().label()).isEqualTo("backend");
    }

    @Test
    void aTitleMatchOutweighsDescriptionMatches() {
        // Backend appears once in the title (3); AI words appear in the description only (1 each).
        assertThat(SummaryPicker.pick(ALL, "Backend Intern", "ai ai pytorch").orElseThrow().label()).isEqualTo("backend");
    }

    @Test
    void noOverlapFallsBackToTheFirstVariantAndWholeWordsOnly() {
        assertThat(SummaryPicker.pick(ALL, "Accountant", "maintain books").orElseThrow().label()).isEqualTo("backend");
    }

    @Test
    void unconfirmedVariantsAreNeverUsed() {
        CvSummary unconfirmed = new CvSummary(9, "x", List.of("backend"), "Unconfirmed.", true, 0);
        assertThat(SummaryPicker.pick(List.of(unconfirmed), "Backend", "")).isEmpty();
        assertThat(SummaryPicker.pick(List.of(unconfirmed, AI), "Backend", "").orElseThrow().label()).isEqualTo("ai");
    }
}
