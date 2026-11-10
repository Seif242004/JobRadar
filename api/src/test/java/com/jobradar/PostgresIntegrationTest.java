package com.jobradar;

import com.jobradar.notifier.internal.TelegramClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need the real schema: boots the api against a Postgres + pgvector
 * container, so Flyway runs exactly as it does in production.
 *
 * <p>The container is started once and shared by every subclass (singleton container pattern),
 * which also lets Spring reuse one application context across the test classes.
 */
// The SQS listener is off: tests call the matcher directly and need no queue.
@SpringBootTest(properties = "jobradar.matching.listener.enabled=false")
abstract class PostgresIntegrationTest {

    // Same image as docker-compose.yml; declared compatible so Testcontainers treats it as Postgres.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    /** No test may reach the real Telegram API. Declared here so all subclasses share one context. */
    @MockitoBean
    TelegramClient telegram;

    static {
        POSTGRES.start();
    }
}
