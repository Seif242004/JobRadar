package com.jobradar.profile;

import java.time.LocalDate;
import java.util.List;

/**
 * One user's search preferences (the {@code profile} table) plus where to reach them.
 *
 * @param telegramChatId null until the user links the Telegram bot
 * @param graduationDate null when unknown
 * @param entryLevelOnly true to reject senior, lead, mid-level and experience-heavy postings
 * @param maxYearsExperience most years of experience a posting may ask for while {@code entryLevelOnly}
 * @param titleKeywords the title must contain one of these; empty means no requirement
 * @param excludeKeywords a title containing one of these is rejected
 * @param maxAgeDays postings older than this many days are rejected; 0 means no limit
 * @param pushMinFit a new posting with at least this fit score is pushed at once
 * @param digestMinFit a posting with at least this fit score (or none) goes into the daily digest
 */
public record UserPreferences(
        long userId,
        Long telegramChatId,
        List<String> roles,
        List<String> cities,
        boolean remote,
        List<String> jobTypes,
        LocalDate graduationDate,
        boolean entryLevelOnly,
        int maxYearsExperience,
        List<String> titleKeywords,
        List<String> excludeKeywords,
        int maxAgeDays,
        int pushMinFit,
        int digestMinFit) {

    /** Default age limit: postings older than a week are hidden. */
    public static final int DEFAULT_MAX_AGE_DAYS = 7;

    /** Preferences with the default age limit. */
    public UserPreferences(
            long userId, Long telegramChatId, List<String> roles, List<String> cities, boolean remote,
            List<String> jobTypes, LocalDate graduationDate, boolean entryLevelOnly, int maxYearsExperience,
            List<String> titleKeywords, List<String> excludeKeywords) {
        this(userId, telegramChatId, roles, cities, remote, jobTypes, graduationDate, entryLevelOnly,
                maxYearsExperience, titleKeywords, excludeKeywords, DEFAULT_MAX_AGE_DAYS, 75, 40);
    }

    /** Preferences without the keyword filters; entry-level rule derived from the graduation date. */
    public UserPreferences(
            long userId,
            Long telegramChatId,
            List<String> roles,
            List<String> cities,
            boolean remote,
            List<String> jobTypes,
            LocalDate graduationDate) {
        this(userId, telegramChatId, roles, cities, remote, jobTypes, graduationDate,
                graduationDate == null || graduationDate.plusYears(2).isAfter(LocalDate.now()), 2, List.of(), List.of());
    }
}
