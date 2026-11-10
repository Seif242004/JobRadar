package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks that Flyway V1 produces the multi-user data model of docs/PLAN.md. */
class SchemaMigrationTests extends PostgresIntegrationTest {

    /** Per-user tables from docs/PLAN.md; each must carry user_id. */
    private static final List<String> PER_USER_TABLES = List.of(
            "profile", "bullet", "cv_entry", "watchlist", "match", "cv_version", "application", "notification");

    /** Shared tables: no user_id. */
    private static final List<String> SHARED_TABLES = List.of("company", "posting", "source_run");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allCoreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("app_user").containsAll(PER_USER_TABLES).containsAll(SHARED_TABLES);
    }

    @Test
    void everyPerUserTableHasUserIdAndSharedTablesDoNot() {
        List<String> withUserId = jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'user_id'",
                String.class);

        assertThat(withUserId).containsExactlyInAnyOrderElementsOf(PER_USER_TABLES);
    }

    @Test
    void deletingAUserRemovesAllOfTheirDataButKeepsSharedPostings() {
        Long userId = jdbc.queryForObject(
                "INSERT INTO app_user (email, daily_apply_cap) VALUES ('cascade@example.com', 5) RETURNING id",
                Long.class);
        Long postingId = jdbc.queryForObject(
                "INSERT INTO posting (source, source_id, title, content_hash) "
                        + "VALUES ('fixture', 'cascade-1', 'Backend Intern', 'h1') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'project', 'Built X')", userId);
        jdbc.update("INSERT INTO match (user_id, posting_id, eligible) VALUES (?, ?, TRUE)", userId, postingId);

        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bullet WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE id = ?", Long.class, postingId))
                .isEqualTo(1);
    }

    @Test
    void embeddingColumnsUseTheDimensionFixedInTheSpec() {
        // pgvector stores the dimension in atttypmod; SPEC.md D3 fixes it at 1024.
        List<Integer> dims = jdbc.queryForList(
                "SELECT atttypmod FROM pg_attribute "
                        + "WHERE attname = 'embedding' AND attrelid IN ('bullet'::regclass, 'posting'::regclass)",
                Integer.class);

        assertThat(dims).containsExactly(1024, 1024);
    }
}
