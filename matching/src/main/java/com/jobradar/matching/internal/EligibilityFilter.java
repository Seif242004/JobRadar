package com.jobradar.matching.internal;

import com.jobradar.profile.UserPreferences;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hard filters of Flow 2 (docs/PLAN.md): deadline not passed, Egypt or remote-from-Egypt, level,
 * job type. They are deterministic keyword rules on purpose: cheap, explainable, and run before
 * any embedding or LLM call. Whether the role itself suits the user is the fit score's job.
 */
public final class EligibilityFilter {

    /** Egypt and its main employment hubs, in English and Arabic. */
    private static final Pattern EGYPT = Pattern.compile(
            "egypt|cairo|giza|alexandria|maadi|heliopolis|nasr city|zamalek|sheikh zayed|smart village"
                    + "|6th of october|october city|new capital|mansoura|مصر|القاهرة|الجيزة|الإسكندرية|الاسكندرية");

    private static final Pattern REMOTE = Pattern.compile("remote|work from home|wfh|anywhere|worldwide|عن بعد|عن بُعد");

    /** Regions a remote role may name that still include Egypt. */
    private static final Pattern REMOTE_INCLUDES_EGYPT =
            Pattern.compile("worldwide|anywhere|global|emea|mena|middle east|africa");

    private static final Pattern SENIOR = Pattern.compile(
            "\\b(senior|sr|staff|principal|lead|manager|head|director|architect|vp|chief)\\b|\\bsr\\.");

    private static final Pattern INTERNSHIP = Pattern.compile("\\bintern(ship)?\\b|trainee|تدريب|متدرب");

    private static final Pattern WORKING_STUDENT =
            Pattern.compile("working student|werkstudent|student job|part[- ]time student");

    /** Until this long after graduation the user is treated as entry level. */
    private static final int ENTRY_LEVEL_YEARS_AFTER_GRADUATION = 2;

    private EligibilityFilter() {}

    public static Eligibility check(PostingView posting, UserPreferences user, Instant now) {
        // 1. Deadline not passed.
        if (posting.deadline() != null && posting.deadline().isBefore(now)) {
            return Eligibility.rejected("Deadline has passed");
        }

        // 2. Egypt, or remote and open to Egypt.
        String location = lower(posting.location());
        String remotePolicy = lower(posting.remotePolicy());
        boolean inEgypt = EGYPT.matcher(location).find() || matchesAny(location, user.cities());
        boolean remote = REMOTE.matcher(remotePolicy).find() || REMOTE.matcher(location).find();
        String where;
        if (inEgypt) {
            where = "In Egypt";
        } else if (remote) {
            if (!user.remote()) {
                return Eligibility.rejected("Remote role, and the profile excludes remote");
            }
            // A remote role that names no place, or a region containing Egypt, is open to Egypt.
            String place = REMOTE.matcher(location).replaceAll("").replaceAll("[^\\p{L}]+", " ").strip();
            if (!place.isEmpty() && !REMOTE_INCLUDES_EGYPT.matcher(location).find()) {
                return Eligibility.rejected("Remote, but restricted to " + posting.location());
            }
            where = "Remote, open to Egypt";
        } else {
            return Eligibility.rejected("Outside Egypt and not remote");
        }

        // 3. Level: an entry-level user is not shown senior roles.
        String title = lower(posting.title());
        if (isEntryLevel(user, now) && SENIOR.matcher(title).find()) {
            return Eligibility.rejected("Senior role for an entry-level profile");
        }

        // 4. Job type must be one the user wants.
        String jobType = jobType(title);
        boolean wanted = user.jobTypes().stream().anyMatch(type -> type.equalsIgnoreCase(jobType));
        if (!wanted) {
            return Eligibility.rejected("Job type " + jobType + " is not in the profile");
        }

        return Eligibility.accepted(where + ", " + jobType);
    }

    /** Job type is read from the title only; descriptions mention "intern" in unrelated ways. */
    static String jobType(String lowerTitle) {
        if (WORKING_STUDENT.matcher(lowerTitle).find()) {
            return "working-student";
        }
        return INTERNSHIP.matcher(lowerTitle).find() ? "internship" : "full-time";
    }

    private static boolean isEntryLevel(UserPreferences user, Instant now) {
        if (user.graduationDate() == null) {
            return false;
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return today.isBefore(user.graduationDate().plusYears(ENTRY_LEVEL_YEARS_AFTER_GRADUATION));
    }

    private static boolean matchesAny(String lowerText, List<String> needles) {
        return needles.stream()
                .map(EligibilityFilter::lower)
                .anyMatch(needle -> !needle.isBlank() && lowerText.contains(needle));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
