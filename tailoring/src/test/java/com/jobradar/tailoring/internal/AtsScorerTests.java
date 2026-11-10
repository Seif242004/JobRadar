package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AtsScorerTests {

    @Test
    void scoresTheShareOfPostingKeywordsFoundInTheCv() {
        // Keywords: title words "java", "backend", "engineer" + repeated description words "docker".
        double score = AtsScorer.score(
                "Java Backend Engineer",
                "You will ship docker images. Docker experience matters. Postgres is nice.",
                "Built Java services in Docker for a backend team");
        // Covered: java, backend, docker. Missing: engineer. Postgres occurs once, so it is no keyword.
        assertThat(AtsScorer.keywords("Java Backend Engineer",
                        "You will ship docker images. Docker experience matters. Postgres is nice."))
                .containsExactlyInAnyOrder("java", "backend", "engineer", "docker");
        assertThat(score).isEqualTo(75.0);
    }

    @Test
    void keepsTokensOfTechnicalNames() {
        assertThat(AtsScorer.tokens("C++ and C#, Node.js; .NET")).contains("c++", "c#", "node.js");
    }

    @Test
    void aPostingWithoutKeywordsScoresZero() {
        assertThat(AtsScorer.score("", "", "anything")).isZero();
        assertThat(AtsScorer.score(null, null, null)).isZero();
    }

    @Test
    void keywordsAreCapped() {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            description.append("word").append(i).append(" word").append(i).append(' ');
        }
        assertThat(AtsScorer.keywords("Engineer", description.toString())).hasSize(AtsScorer.MAX_KEYWORDS);
    }
}
