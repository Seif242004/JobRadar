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
            SELECT u.id, u.telegram_chat_id, p.roles, p.cities, p.remote, p.job_types, p.graduation_date
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
                graduation == null ? null : graduation.toLocalDate());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
