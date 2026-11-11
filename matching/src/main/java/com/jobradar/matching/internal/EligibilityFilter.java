package com.jobradar.matching.internal;

import com.jobradar.profile.UserPreferences;
import java.time.Instant;
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

    /** Mid-level markers that an entry-level profile should not see: "Engineer II", "Mid-level", "experienced". */
    private static final Pattern MID_LEVEL =
            Pattern.compile("\\b(ii|iii|iv|mid[- ]?level|mid[- ]?senior|experienced|intermediate)\\b");

    /** "3+ years", "5 years of experience", "minimum 4 yrs". The first number is the lower bound asked for. */
    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:-\\s*\\d{1,2}\\s*)?(?:years?|yrs?)\\b");

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

        // 3. Level: an entry-level user is not shown senior, mid-level or experience-heavy roles.
        String title = lower(posting.title());
        if (user.entryLevelOnly()) {
            if (SENIOR.matcher(title).find()) {
                return Eligibility.rejected("Senior role for an entry-level profile");
            }
            if (MID_LEVEL.matcher(title).find()) {
                return Eligibility.rejected("Mid-level role for an entry-level profile");
            }
            int years = yearsAsked(posting.description());
            if (years > user.maxYearsExperience() && !INTERNSHIP.matcher(title).find()) {
                return Eligibility.rejected("Asks for " + years + "+ years of experience");
            }
        }

        // 3b. Roles the user excluded, and roles that match none of the titles the user wants.
        for (String word : user.excludeKeywords()) {
            if (containsWord(title, word)) {
                return Eligibility.rejected("Title has excluded word \"" + word + "\"");
            }
        }
        if (!user.titleKeywords().isEmpty()
                && user.titleKeywords().stream().noneMatch(word -> containsWord(title, word))) {
            return Eligibility.rejected("Title matches none of your target roles");
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

    /**
     * Smallest years-of-experience figure the description asks for, 0 when it names none. The
     * minimum of all matches is used so "3+ years Java, 1 year Spring" counts as 1, which keeps the
     * filter from rejecting a posting on a single demanding line.
     */
    static int yearsAsked(String description) {
        if (description == null || description.isBlank()) {
            return 0;
        }
        String text = description.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = YEARS.matcher(text);
        int min = Integer.MAX_VALUE;
        while (matcher.find()) {
            // Only figures that talk about experience count; "10 years in business" is company copy.
            // A "+" ("3+ years") or the word "experience" within 40 characters either side qualifies.
            int from = Math.max(0, matcher.start() - 40);
            int to = Math.min(text.length(), matcher.end() + 40);
            if (matcher.group().contains("+") || text.substring(from, to).contains("experience")) {
                min = Math.min(min, Integer.parseInt(matcher.group(1)));
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Whole-word (or whole-phrase) match, so "ai" does not match "maintain" and "lead" not "leadership". */
    static boolean containsWord(String lowerText, String word) {
        String needle = lower(word).strip();
        if (needle.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])")
                .matcher(lowerText)
                .find();
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
