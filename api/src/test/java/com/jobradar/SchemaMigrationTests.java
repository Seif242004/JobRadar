package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the api against a real Postgres + pgvector container, so Flyway V1 is applied exactly as
 * it will be in production, then checks the multi-user rules of the data model.
 */
@SpringBootTest
@Testcontainers
class SchemaMigrationTests {

    // Same image as docker-compose.yml; declared compatible so Testcontainers treats it as Postgres.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    /** Per-user tables from docs/PLAN.md; each must carry user_id. */
    private static final List<String> PER_USER_TABLES = List.of(
            "profile", "bullet", "watchlist", "match", "cv_version", "application", "notification");

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
                "INSERT INTO app_user (email, daily_apply_cap) VALUES ('u@example.com', 5) RETURNING id",
                Long.class);
        Long postingId = jdbc.queryForObject(
                "INSERT INTO posting (source, source_id, title, content_hash) "
                        + "VALUES ('fixture', '1', 'Backend Intern', 'h1') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO bullet (user_id, section, text) VALUES (?, 'project', 'Built X')", userId);
        jdbc.update("INSERT INTO match (user_id, posting_id, eligible) VALUES (?, ?, TRUE)", userId, postingId);

        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bullet", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting", Long.class)).isEqualTo(1);
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
