package com.jobradar.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostingLevelTests {

    @Test
    void internshipsAndWorkingStudentsAreStudentRoles() {
        assertThat(PostingLevel.ids("Software Engineering Intern", "")).containsExactly("student");
        assertThat(PostingLevel.ids("Working Student - Software Development", "")).containsExactly("student");
    }

    @Test
    void graduateProgramsAreFreshGrad() {
        assertThat(PostingLevel.ids("VOIS Graduate Program 2027", "")).containsExactly("fresh-grad");
        assertThat(PostingLevel.ids("Software Engineer", "Ideal for fresh graduates.")).containsExactly("fresh-grad");
    }

    @Test
    void juniorIsSeparateFromGraduate() {
        assertThat(PostingLevel.ids("Junior Backend Developer", "")).containsExactly("junior");
    }

    @Test
    void traineesAndNoDegreeRolesAreNonGraduate() {
        assertThat(PostingLevel.ids("Trainee Developer", "")).containsExactly("non-graduate");
        assertThat(PostingLevel.ids("Support Engineer", "No degree required.")).containsExactly("non-graduate");
    }

    @Test
    void aPostingCanBeOpenToSeveralGroups() {
        assertThat(PostingLevel.ids("Graduate or Intern Engineer", "")).containsExactlyInAnyOrder("student", "fresh-grad");
    }

    @Test
    void aPlainTitleHasNoLevel() {
        assertThat(PostingLevel.ids("Software Engineer", "Build services.")).isEmpty();
    }
}
