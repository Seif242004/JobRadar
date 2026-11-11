package com.jobradar.tailoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoInventionTests {

    private static final String CORPUS =
            "Software Engineering Intern · e& (Etisalat). Built Spring Boot services with PostgreSQL and Docker. "
                    + "FitCrew is a live PWA with 15 active users. German University in Cairo, expected 2027. Node.js and C++.";

    @Test
    void textMadeOfProfileTermsPasses() {
        String text = "Computer engineering student at the German University in Cairo (expected 2027). Builds Spring Boot "
                + "services with PostgreSQL and Docker, and shipped FitCrew, a live PWA with 15 active users. Uses Node.js and C++.";

        assertThat(NoInvention.unknownTerms(text, CORPUS)).isEmpty();
    }

    @Test
    void anInventedEmployerSkillOrNumberIsReported() {
        assertThat(NoInvention.unknownTerms("Interned at Google and shipped Kubernetes clusters.", CORPUS))
                .containsExactly("Google", "Kubernetes");
        assertThat(NoInvention.unknownTerms("Served 5000 users with Spring Boot.", CORPUS)).containsExactly("5000");
    }

    @Test
    void theFirstWordOfASentenceIsNotTreatedAsAProperNoun() {
        assertThat(NoInvention.unknownTerms("Builds things. Ships fast.", CORPUS)).isEmpty();
    }
}
