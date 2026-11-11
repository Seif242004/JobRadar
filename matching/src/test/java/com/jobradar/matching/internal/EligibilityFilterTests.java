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
        // The description carries the early-career signal most tests are not about.
        return new PostingView(1L, title, location, remotePolicy, "Open to fresh graduates.", deadline, null, NOW);
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

    private static UserPreferences prefs(List<String> titleKeywords, List<String> excludeKeywords, int maxYears) {
        return new UserPreferences(
                1L, 42L, List.of(), List.of("Cairo"), true, List.of("internship", "full-time"),
                LocalDate.of(2027, 6, 30), true, maxYears, titleKeywords, excludeKeywords);
    }

    private static PostingView withDescription(String title, String description) {
        // Most tests here are not about the early-career signal, so every description carries one.
        String text = (description == null ? "" : description + " ") + "Fresh graduates welcome.";
        return new PostingView(1L, title, "Cairo", null, text, null, null, NOW);
    }

    @Test
    void midLevelTitlesAndExperienceHeavyDescriptionsAreRejectedForEntryLevel() {
        UserPreferences user = prefs(List.of(), List.of(), 2);

        assertThat(EligibilityFilter.check(withDescription("Software Engineer II", null), user, NOW).eligible()).isFalse();
        assertThat(EligibilityFilter.check(withDescription("Backend Developer", "Requires 5+ years of experience in Java"), user, NOW)
                        .reason())
                .isEqualTo("Asks for 5+ years of experience");
        // Two years is within the limit, and an internship is never rejected on years.
        assertThat(EligibilityFilter.check(withDescription("Backend Developer", "1-2 years of experience"), user, NOW).eligible()).isTrue();
        assertThat(EligibilityFilter.check(withDescription("Backend Intern", "3+ years of experience preferred"), user, NOW).eligible()).isTrue();
    }

    @Test
    void yearsAskedUsesTheSmallestFigureAndIgnoresCompanyCopy() {
        assertThat(EligibilityFilter.yearsAsked("3+ years Java, 1 year of experience with Spring")).isEqualTo(1);
        assertThat(EligibilityFilter.yearsAsked("We have 10 years in business")).isEqualTo(0);
        assertThat(EligibilityFilter.yearsAsked(null)).isEqualTo(0);
    }

    @Test
    void titleKeywordsAreRequiredAndExcludedWordsRejectOnWholeWords() {
        UserPreferences user = prefs(List.of("backend", "ai", "game"), List.of("sales"), 2);

        assertThat(EligibilityFilter.check(withDescription("Backend Engineer", null), user, NOW).eligible()).isTrue();
        assertThat(EligibilityFilter.check(withDescription("AI Engineer Intern", null), user, NOW).eligible()).isTrue();
        // "ai" must not match inside "maintenance".
        assertThat(EligibilityFilter.check(withDescription("Maintenance Technician", null), user, NOW).reason())
                .isEqualTo("Title matches none of your target roles");
        assertThat(EligibilityFilter.check(withDescription("Backend Sales Engineer", null), user, NOW).reason())
                .isEqualTo("Title has excluded word \"sales\"");
    }

    @Test
    void entryLevelCanBeSwitchedOff() {
        UserPreferences user = new UserPreferences(
                1L, 42L, List.of(), List.of("Cairo"), true, List.of("full-time"), null, false, 2, List.of(), List.of());

        assertThat(EligibilityFilter.check(withDescription("Senior Engineer", "8+ years of experience"), user, NOW).eligible()).isTrue();
    }

    @Test
    void aPostingWithNoEarlyCareerSignalIsRejectedForAnEntryLevelProfile() {
        PostingView plain = new PostingView(1L, "Software Engineer", "Cairo", null, "Build services.", null, null, NOW);

        Eligibility result = EligibilityFilter.check(plain, STUDENT, NOW);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("No internship, graduate or junior signal in the posting");
    }

    @Test
    void graduateProgramsAndWorkingStudentRolesAreRecognisedFromTheTitle() {
        assertThat(EligibilityFilter.check(new PostingView(1L, "VOIS Graduate Program 2027", "Cairo", null, "", null, null, NOW), STUDENT, NOW).eligible())
                .isTrue();
        UserPreferences students = new UserPreferences(
                1L, 42L, List.of(), List.of("Cairo"), true, List.of("working-student"), LocalDate.of(2027, 6, 30));
        assertThat(EligibilityFilter.check(new PostingView(1L, "Working Student - Software Development", "Cairo", null, "", null, null, NOW), students, NOW).reason())
                .isEqualTo("In Egypt, working-student");
        assertThat(EligibilityFilter.check(new PostingView(1L, "Siemens Software Engineering Internship", "Cairo", null, "", null, null, NOW), STUDENT, NOW).eligible())
                .isTrue();
    }

    @Test
    void aDescriptionPhraseForFreshGraduatesIsASignal() {
        PostingView view = new PostingView(1L, "Software Engineer", "Cairo", null, "Ideal for fresh graduates.", null, null, NOW);

        assertThat(EligibilityFilter.check(view, STUDENT, NOW).eligible()).isTrue();
    }

    @Test
    void salesAndOtherNonTechTitlesAreRejectedEvenWithADeveloperWord() {
        Eligibility result = check("Sales - Pharmacies Channel Developer Intern", "Cairo", null);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("Not a software or tech role");
    }

    @Test
    void postingsOlderThanTheAgeLimitAreRejectedUsingTheirOwnDateFirst() {
        Instant old = NOW.minus(Duration.ofDays(8));
        PostingView seenOld = new PostingView(1L, "Backend Intern", "Cairo", null, "Open to fresh graduates.", null, null, old);
        PostingView postedOld = new PostingView(1L, "Backend Intern", "Cairo", null, "Open to fresh graduates.", null, old, NOW);
        PostingView postedRecently = new PostingView(1L, "Backend Intern", "Cairo", null, "Open to fresh graduates.", null, NOW.minus(Duration.ofDays(2)), old);

        assertThat(EligibilityFilter.check(seenOld, STUDENT, NOW).reason()).isEqualTo("Older than 7 days");
        assertThat(EligibilityFilter.check(postedOld, STUDENT, NOW).eligible()).isFalse();
        assertThat(EligibilityFilter.check(postedRecently, STUDENT, NOW).eligible()).isTrue();
    }
}
