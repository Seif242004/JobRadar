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
        List<String> excludeKeywords) {

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
