package com.salespipe.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T2.7 acceptance test — Kafka-consumer half of the plan's "PATCH /deals/{id}/stage ->
 * outbox -> CDC -> Kafka -> activity + notification consumers both fire idempotently"
 * accept line.
 *
 * <p>Publishes a real {@link Topics#DEAL_STAGE_CHANGED} envelope via {@link
 * EventPublisher} against real Kafka + Schema Registry + Postgres (Testcontainers) —
 * the same "publish as if CDC had relayed it" approach {@code ActivityConsumerIT} (T2.5)
 * and {@code IdempotentConsumerIT} (T2.4) use, and the documented substitute for a real
 * Debezium hop (never Testcontainers-tested even in T2.3 — see {@code
 * debezium/README.md} — that gap is accepted, not re-solved here). This class does NOT
 * re-prove the same-transaction outbox write; see {@code
 * pipeline.DealStageChangeOutboxIT} for the real-HTTP half of the accept line.
 *
 * <p>Proves three things: (1) {@code DealStageChangedActivityConsumer} (T2.5, already
 * covered by {@code ActivityConsumerIT}, re-asserted here so both consumers' reaction to
 * one publish is visible in the same test) and {@code
 * DealStageChangedNotificationConsumer} (new in T2.7) both fire off the SAME event; (2)
 * the notification row is addressed to the deal's owner and carries the event payload;
 * (3) redelivering the identical event (same {@code eventId}, simulating a Kafka
 * consumer-group replay) does not double-notify or double-log — each consumer dedupes
 * independently via its own {@code processed_events} row (overview §5 "Don't let the
 * notification consumer double-notify on redelivery" risk callout).
 */
class DealStageChangedConsumersIT extends PostgresRedisTestBase {

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

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.eventing.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.eventing.schema-registry-url",
            () -> "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EventPublisher eventPublisher;
    @Autowired ObjectMapper objectMapper;

    UUID orgId;
    UUID stageId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Deal Stage Consumers Test Org", "FREE");
        stageId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_stages (id, org_id, name, position) VALUES (?, ?, ?, ?)",
            stageId, orgId, "Qualified", 0);
    }

    @Test
    void dealStageChangedFiresBothActivityAndNotificationConsumersIdempotently() throws Exception {
        UUID ownerId = UUID.randomUUID();
        // deals.owner_id FKs users(id) -- a real user row is required, not just a bare UUID.
        jdbc.update("INSERT INTO users (id, org_id, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
            ownerId, orgId, "owner-" + ownerId + "@acme.com", "hash", "SALES_REP");
        UUID dealId = UUID.randomUUID();
        jdbc.update("INSERT INTO deals (id, org_id, stage_id, owner_id) VALUES (?, ?, ?, ?)",
            dealId, orgId, stageId, ownerId);

        UUID fromStageId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dealId", dealId.toString());
        payload.put("fromStageId", fromStageId.toString());
        payload.put("toStageId", stageId.toString());
        payload.put("changedBy", ownerId.toString());

        UUID eventId = UUID.randomUUID();
        EventEnvelope event = new EventEnvelope(
            eventId, Topics.DEAL_STAGE_CHANGED, 1, orgId.toString(), "deal", dealId.toString(),
            java.time.Instant.now(), "trace-dsc-consumers-1", payload
        );

        // First delivery: as if Debezium had just relayed the outbox row (see class
        // javadoc for why a direct EventPublisher publish is the documented equivalent).
        eventPublisher.publish(Topics.DEAL_STAGE_CHANGED, event).get(30, TimeUnit.SECONDS);

        awaitRowCount(
            "SELECT COUNT(*) FROM activities WHERE org_id = ? AND entity_type = 'deal' "
                + "AND entity_id = ? AND activity_type = 'STAGE_CHANGE'",
            1, orgId, dealId
        );
        awaitRowCount(
            "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND user_id = ? "
                + "AND type = 'DEAL_STAGE_CHANGED'",
            1, orgId, ownerId
        );

        // notifications payload carries the event payload verbatim.
        String notificationPayload = jdbc.queryForObject(
            "SELECT payload::text FROM notifications WHERE org_id = ? AND user_id = ? AND type = 'DEAL_STAGE_CHANGED'",
            String.class, orgId, ownerId);
        assertThat(notificationPayload).contains(dealId.toString()).contains(stageId.toString());

        // Redeliver the identical event (same eventId) -- simulates offset replay.
        eventPublisher.publish(Topics.DEAL_STAGE_CHANGED, event).get(30, TimeUnit.SECONDS);
        Thread.sleep(3000);

        Integer activityCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE org_id = ? AND entity_type = 'deal' "
                + "AND entity_id = ? AND activity_type = 'STAGE_CHANGE'",
            Integer.class, orgId, dealId);
        Integer notificationCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND user_id = ? AND type = 'DEAL_STAGE_CHANGED'",
            Integer.class, orgId, ownerId);

        assertThat(activityCount).isEqualTo(1);
        assertThat(notificationCount).isEqualTo(1);

        Integer activityDedupeRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
            Integer.class, "activity-deal-stage-changed", eventId);
        Integer notificationDedupeRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
            Integer.class, DealStageChangedNotificationConsumer.GROUP, eventId);

        assertThat(activityDedupeRows).isEqualTo(1);
        assertThat(notificationDedupeRows).isEqualTo(1);
    }

    @Test
    void notificationRowGetsNullUserIdWhenDealOwnerIsUnset() throws Exception {
        UUID dealId = UUID.randomUUID();
        jdbc.update("INSERT INTO deals (id, org_id, stage_id) VALUES (?, ?, ?)", dealId, orgId, stageId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dealId", dealId.toString());
        payload.putNull("fromStageId");
        payload.put("toStageId", stageId.toString());

        EventEnvelope event = EventEnvelope.of(
            Topics.DEAL_STAGE_CHANGED, 1, orgId.toString(), "deal", dealId.toString(),
            "trace-dsc-consumers-2", payload
        );
        eventPublisher.publish(Topics.DEAL_STAGE_CHANGED, event).get(30, TimeUnit.SECONDS);

        awaitRowCount(
            "SELECT COUNT(*) FROM notifications WHERE org_id = ? AND type = 'DEAL_STAGE_CHANGED' "
                + "AND user_id IS NULL AND payload->>'dealId' = ?",
            1, orgId, dealId.toString()
        );
    }

    private void awaitRowCount(String sql, int expected, Object... params) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Integer count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(sql, Integer.class, params);
            if (count != null && count >= expected) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(count).isEqualTo(expected);
    }
}
