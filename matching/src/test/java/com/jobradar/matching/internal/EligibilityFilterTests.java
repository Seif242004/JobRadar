package com.jobradar.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.profile.UserPreferences;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hard filters of Flow 2: deadline, Egypt or remote-from-Egypt, level, job type. */
class EligibilityFilterTests {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    /** A student graduating in 2027 who wants internships and full-time roles, remote allowed. */
    private static final UserPreferences STUDENT = new UserPreferences(
            1L, 42L, List.of("Backend Engineer"), List.of("Cairo"), true,
            List.of("internship", "full-time"), LocalDate.of(2027, 6, 30));

    private static PostingView posting(String title, String location, String remotePolicy, Instant deadline) {
        return new PostingView(1L, title, location, remotePolicy, null, deadline, null, NOW);
    }

    private static Eligibility check(String title, String location, String remotePolicy) {
        return EligibilityFilter.check(posting(title, location, remotePolicy, null), STUDENT, NOW);
    }

    @Test
    void postingsInEgyptAreEligibleInEnglishAndArabic() {
        assertThat(check("Backend Engineer", "Cairo, Egypt", "onsite").eligible()).isTrue();
        assertThat(check("Backend Engineer", "Smart Village, Giza", null).eligible()).isTrue();
        assertThat(check("مهندس برمجيات", "القاهرة", null).eligible()).isTrue();
    }

    @Test
    void postingsAbroadThatAreNotRemoteAreRejected() {
        Eligibility result = check("Backend Engineer", "Berlin, Germany", "onsite");

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("Outside Egypt and not remote");
    }

    @Test
    void remoteRolesAreEligibleWhenTheyNameNoPlaceOrARegionThatIncludesEgypt() {
        assertThat(check("Backend Engineer", "Remote", "remote").eligible()).isTrue();
        assertThat(check("Backend Engineer", "Worldwide", "remote").eligible()).isTrue();
        assertThat(check("Backend Engineer", "Remote - EMEA", "remote").eligible()).isTrue();
        assertThat(check("Backend Engineer", null, "remote").eligible()).isTrue();
    }

    @Test
    void remoteRolesRestrictedToAnotherCountryAreRejected() {
        Eligibility result = check("Backend Engineer", "Remote - USA only", "remote");

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).startsWith("Remote, but restricted to");
    }

    @Test
    void remoteRolesAreRejectedWhenTheProfileExcludesRemote() {
        UserPreferences onsiteOnly = new UserPreferences(
                1L, 42L, List.of(), List.of("Cairo"), false, List.of("full-time"), LocalDate.of(2027, 6, 30));

        assertThat(EligibilityFilter.check(posting("Backend Engineer", "Remote", "remote", null), onsiteOnly, NOW)
                        .eligible())
                .isFalse();
    }

    @Test
    void seniorRolesAreRejectedForAnEntryLevelProfileOnly() {
        assertThat(check("Senior Backend Engineer", "Cairo", null).eligible()).isFalse();
        assertThat(check("Sr. Software Engineer", "Cairo", null).eligible()).isFalse();
        assertThat(check("Engineering Manager", "Cairo", null).eligible()).isFalse();
        // "Leading" or "Leadership" must not trip the word-bounded "lead" rule.
        assertThat(check("Software Engineer, Leadership Tools", "Cairo", null).eligible()).isTrue();

        UserPreferences experienced = new UserPreferences(
                2L, 43L, List.of(), List.of("Cairo"), true, List.of("full-time"), LocalDate.of(2018, 6, 30));
        assertThat(EligibilityFilter.check(posting("Senior Backend Engineer", "Cairo", null, null), experienced, NOW)
                        .eligible())
                .isTrue();
    }

    @Test
    void jobTypeMustBeOneTheProfileWants() {
        assertThat(check("Backend Engineering Intern", "Cairo", null).reason()).isEqualTo("In Egypt, internship");
        assertThat(check("Software Engineer", "Cairo", null).reason()).isEqualTo("In Egypt, full-time");

        Eligibility workingStudent = check("Working Student - Backend", "Cairo", null);
        assertThat(workingStudent.eligible()).isFalse();
        assertThat(workingStudent.reason()).isEqualTo("Job type working-student is not in the profile");
    }

    @Test
    void aPassedDeadlineRejectsAndAFutureOneDoesNot() {
        assertThat(EligibilityFilter.check(
                                posting("Summer Internship", "Cairo", null, NOW.minus(Duration.ofHours(1))), STUDENT, NOW)
                        .reason())
                .isEqualTo("Deadline has passed");
        assertThat(EligibilityFilter.check(
                                posting("Summer Internship", "Cairo", null, NOW.plus(Duration.ofDays(5))), STUDENT, NOW)
                        .eligible())
                .isTrue();
    }
}
