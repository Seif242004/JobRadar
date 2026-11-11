package com.jobradar.profile;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Read access to user profiles for the other modules. */
@Service
public class ProfileService {

    private static final String SELECT =
            """
            SELECT u.id, u.telegram_chat_id, p.roles, p.cities, p.remote, p.job_types, p.graduation_date,
                   p.entry_level_only, p.max_years_experience, p.title_keywords, p.exclude_keywords, p.max_age_days, p.push_min_fit, p.digest_min_fit
            FROM app_user u JOIN profile p ON p.user_id = u.id
            """;

    private static final RowMapper<UserPreferences> MAPPER = ProfileService::map;

    private final JdbcTemplate jdbc;

    ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every user that has a profile. Postings are matched against each of them. */
    public List<UserPreferences> findAllPreferences() {
        return jdbc.query(SELECT + " ORDER BY u.id", MAPPER);
    }

    public Optional<UserPreferences> findPreferences(long userId) {
        return jdbc.query(SELECT + " WHERE u.id = ?", MAPPER, userId).stream().findFirst();
    }

    /** Whether the user has confirmed the preferences once; the dashboard shows onboarding until then. */
    public boolean isOnboarded(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT onboarded FROM profile WHERE user_id = ?", Boolean.class, userId));
    }

    /** Replaces the editable search preferences and marks the user as onboarded. */
    public void savePreferences(long userId, EditablePreferences prefs) {
        jdbc.update(
                """
                UPDATE profile SET roles = ?::text[], cities = ?::text[], remote = ?, job_types = ?::text[],
                    entry_level_only = ?, max_years_experience = ?, title_keywords = ?::text[],
                    exclude_keywords = ?::text[], max_age_days = ?, push_min_fit = ?, digest_min_fit = ?, onboarded = TRUE
                WHERE user_id = ?
                """,
                literal(prefs.roles()), literal(prefs.cities()), prefs.remote(), literal(prefs.jobTypes()),
                prefs.entryLevelOnly(), prefs.maxYearsExperience(), literal(prefs.titleKeywords()),
                literal(prefs.excludeKeywords()), prefs.maxAgeDays(), prefs.pushMinFit(), prefs.digestMinFit(), userId);
    }

    /** The preferences a user may edit; everything else on the profile is fixed by the seed. */
    public record EditablePreferences(
            List<String> roles,
            List<String> cities,
            boolean remote,
            List<String> jobTypes,
            boolean entryLevelOnly,
            int maxYearsExperience,
            List<String> titleKeywords,
            List<String> excludeKeywords,
            int maxAgeDays,
            int pushMinFit,
            int digestMinFit) {}

    /** A Postgres array literal; every element is quoted so commas and braces in it are harmless. */
    private static String literal(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (String value : values) {
            String clean = value == null ? "" : value.strip();
            if (clean.isEmpty()) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(clean.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static UserPreferences map(ResultSet rs, int rowNum) throws SQLException {
        long chatId = rs.getLong("telegram_chat_id");
        Long telegramChatId = rs.wasNull() ? null : chatId;
        Date graduation = rs.getDate("graduation_date");
        return new UserPreferences(
                rs.getLong("id"),
                telegramChatId,
                strings(rs.getArray("roles")),
                strings(rs.getArray("cities")),
                rs.getBoolean("remote"),
                strings(rs.getArray("job_types")),
                graduation == null ? null : graduation.toLocalDate(),
                rs.getBoolean("entry_level_only"),
                rs.getInt("max_years_experience"),
                strings(rs.getArray("title_keywords")),
                strings(rs.getArray("exclude_keywords")),
                rs.getInt("max_age_days"),
                rs.getInt("push_min_fit"),
                rs.getInt("digest_min_fit"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
