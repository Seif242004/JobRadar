package com.jobradar.profile;

import java.time.LocalDate;
import java.util.List;

/**
 * One user's search preferences (the {@code profile} table) plus where to reach them.
 *
 * @param telegramChatId null until the user links the Telegram bot
 * @param graduationDate null when unknown
 */
public record UserPreferences(
        long userId,
        Long telegramChatId,
        List<String> roles,
        List<String> cities,
        boolean remote,
        List<String> jobTypes,
        LocalDate graduationDate) {}
