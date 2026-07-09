package com.salespipe.support;

import com.redis.testcontainers.RedisContainer;
import com.salespipe.admin.TenantDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for integration tests needing real Postgres + Redis (T4.8 consolidation).
 *
 * <h2>Singleton containers</h2>
 * The Postgres and Redis containers are {@code static} and started ONCE (in the static
 * initializer), then reused across every test class that extends this base — the
 * Testcontainers "singleton container" pattern. They are deliberately never stopped in an
 * {@code @AfterAll}; Ryuk (or the JVM exit) reaps them. This turns dozens of per-class
 * container starts into two total, which is the bulk of the suite's wall-clock time.
 *
 * <h2>Per-test isolation</h2>
 * Because the database is now shared across classes, state would leak between them — which
 * is exactly the bug this consolidation fixes (tests using fixed emails like
 * {@code admin@acme.com} collided on the {@code UNIQUE (org_id, email)} constraint when a
 * later class re-inserted them). {@link #truncateTenantTables()} runs before every test and
 * truncates every org-scoped table, so each test starts from a clean, empty schema
 * regardless of what ran before it. The Flyway-managed schema itself (tables, functions,
 * default partitions) is left intact — only row data is cleared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers // so subclasses' own @Container fields (e.g. Kafka) are still managed
public abstract class PostgresRedisTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static final RedisContainer REDIS =
        new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static {
        // Start once; reused across all subclasses. Never explicitly stopped (Ryuk reaps).
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate baseJdbc;

    /**
     * Clears all row data before each test so the shared DB behaves like a fresh one.
     * Uses the same authoritative org-scoped table list as the GDPR delete, plus
     * {@code processed_events} (the one non-org table, still worth clearing between tests),
     * TRUNCATE ... CASCADE so FK order doesn't matter.
     */
    @BeforeEach
    void truncateTenantTables() {
        StringBuilder tables = new StringBuilder();
        for (String t : TenantDeletionService.ORG_SCOPED_TABLES_DELETE_ORDER) {
            tables.append(t).append(", ");
        }
        tables.append("organizations, processed_events");
        baseJdbc.execute("TRUNCATE TABLE " + tables + " RESTART IDENTITY CASCADE");
    }
}
