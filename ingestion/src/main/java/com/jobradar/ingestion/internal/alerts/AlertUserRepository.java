package com.jobradar.ingestion.internal.alerts;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Users who connected their Gmail ({@code app_user.gmail_token_ref} is set). Read-only. */
@Repository
class AlertUserRepository {

    /** A user whose alert label is read. The reference is a Secrets Manager name, never a token. */
    record AlertUser(long id, String tokenRef) {}

    private final JdbcTemplate jdbc;

    AlertUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AlertUser> usersWithGmail() {
        return jdbc.query(
                "SELECT id, gmail_token_ref FROM app_user WHERE gmail_token_ref IS NOT NULL ORDER BY id",
                (row, i) -> new AlertUser(row.getLong("id"), row.getString("gmail_token_ref")));
    }
}
