package com.salespipe.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T3.5 accept: "crossing threshold produces a notification (idempotently)". A {@code
 * lead.score.updated} event at/above {@code app.scoring.hot-lead-threshold} produces one
 * {@code HOT_LEAD} notification row; a redelivery of the same event produces no second
 * row; a below-threshold event produces none.
 */
class HotLeadNotificationIT extends PostgresRedisTestBase {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
        .withNetwork(NETWORK)
        .withNetworkAliases("kafka")
        .withListener("kafka:9095");

    static GenericContainer<?> schemaRegistry = new GenericContainer<>(
            DockerImageName.parse("confluentinc/cp-schema-registry:7.7.1"))
        .withNetwork(NETWORK)
        .withNetworkAliases("schema-registry")
        .withExposedPorts(8081)
        .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
        .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:9095")
        .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
        .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @BeforeAll
    static void startSchemaRegistry() {
        schemaRegistry.start();
    }

    @AfterAll
    static void stopSchemaRegistry() {
        schemaRegistry.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.eventing.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.eventing.schema-registry-url",
            () -> "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
        registry.add("app.scoring.hot-lead-threshold", () -> "0.75");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EventPublisher eventPublisher;
    @Autowired ObjectMapper objectMapper;

    UUID orgId;
    UUID ownerId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Hot Lead Test Org", "FREE");
        jdbc.update("INSERT INTO users (id, org_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SALES_REP')",
            ownerId, orgId, "owner-" + ownerId + "@t.co", "x");
    }

    @Test
    void aboveThresholdNotifiesOnceEvenOnRedelivery() throws Exception {
        UUID leadId = insertLead();
        UUID eventId = UUID.randomUUID();
        EventEnvelope event = scoreEvent(eventId, leadId, 0.91);

        eventPublisher.publish(Topics.LEAD_SCORE_UPDATED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);
        eventPublisher.publish(Topics.LEAD_SCORE_UPDATED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        awaitHotLeadCount(orgId, 1);
    }

    @Test
    void belowThresholdDoesNotNotify() throws Exception {
        UUID leadId = insertLead();
        EventEnvelope event = scoreEvent(UUID.randomUUID(), leadId, 0.40);

        eventPublisher.publish(Topics.LEAD_SCORE_UPDATED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        Thread.sleep(3000); // allow the consumer to run and (correctly) skip
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND type = 'HOT_LEAD'",
            Integer.class, orgId);
        assertThat(count).isZero();
    }

    private UUID insertLead() {
        UUID leadId = UUID.randomUUID();
        jdbc.update("INSERT INTO leads (id, org_id, status, owner_id, version) VALUES (?, ?, 'NEW', ?, 0)",
            leadId, orgId, ownerId);
        return leadId;
    }

    private EventEnvelope scoreEvent(UUID eventId, UUID leadId, double score) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("leadId", leadId.toString());
        payload.put("score", score);
        payload.put("modelVersion", "leadscore/Production/v7");
        return new EventEnvelope(eventId, Topics.LEAD_SCORE_UPDATED, 1, orgId.toString(),
            "lead", leadId.toString(), Instant.now(), "trace-score-1", payload);
    }

    private void awaitHotLeadCount(UUID orgId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Integer count = null;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND type = 'HOT_LEAD'",
                Integer.class, orgId);
            if (count != null && count >= expected) {
                break;
            }
            Thread.sleep(300);
        }
        Thread.sleep(1000); // let a redelivery try (and fail) to double-notify
        count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND type = 'HOT_LEAD'",
            Integer.class, orgId);
        assertThat(count).isEqualTo(expected);
    }
}
