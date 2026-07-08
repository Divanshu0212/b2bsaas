package com.salespipe.activity.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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
 * T2.5 acceptance test: "Drag a deal -> within moments its activity timeline shows a
 * STAGE_CHANGE entry (written by a consumer, not the request thread)" — the phase-2
 * plan's own demo script for this task, and the T2.5 accept line "stage drag -> timeline
 * shows STAGE_CHANGE written by consumer".
 *
 * <p>Publishes a real {@link Topics#DEAL_STAGE_CHANGED} envelope via {@link
 * EventPublisher} (same direct-produce path {@link
 * com.salespipe.eventing.consumer.IdempotentConsumerIT} uses) against real Kafka +
 * Schema Registry + Postgres (Testcontainers), and asserts the {@code
 * DealStageChangedActivityConsumer}'s async handler appended a {@code STAGE_CHANGE} row
 * to {@code activities} — i.e. the write happens on the consumer thread, not the
 * request/test thread, which is exactly what "not the request thread" means here: the
 * assertion polls until the row appears rather than reading it synchronously right after
 * publish.
 *
 * <p>Also covers {@code lead.created} (T2.5's second required topic) and {@code
 * activity.logged} (the third) in the same style, and the {@code
 * activity_type}-from-payload behavior of {@link ActivityLoggedConsumer}.
 */
class ActivityConsumerIT extends PostgresRedisTestBase {

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

    @BeforeEach
    void setUpOrg() {
        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Activity Consumer Test Org", "FREE");
    }

    @Test
    void dealStageChangedEventProducesStageChangeTimelineRowWrittenByConsumer() throws Exception {
        UUID dealId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dealId", dealId.toString());
        payload.put("fromStageId", UUID.randomUUID().toString());
        payload.put("toStageId", UUID.randomUUID().toString());

        EventEnvelope event = EventEnvelope.of(
            Topics.DEAL_STAGE_CHANGED, 1, orgId.toString(), "deal", dealId.toString(),
            "trace-stage-1", payload
        );
        eventPublisher.publish(Topics.DEAL_STAGE_CHANGED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        awaitRowCount(
            "SELECT COUNT(*) FROM activities WHERE org_id = ? AND entity_type = 'deal' "
                + "AND entity_id = ? AND activity_type = 'STAGE_CHANGE'",
            orgId, dealId
        );
    }

    @Test
    void leadCreatedEventProducesLeadCreatedTimelineRow() throws Exception {
        UUID leadId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("leadId", leadId.toString());
        payload.put("source", "web");

        EventEnvelope event = EventEnvelope.of(
            Topics.LEAD_CREATED, 1, orgId.toString(), "lead", leadId.toString(),
            "trace-lead-1", payload
        );
        eventPublisher.publish(Topics.LEAD_CREATED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        awaitRowCount(
            "SELECT COUNT(*) FROM activities WHERE org_id = ? AND entity_type = 'lead' "
                + "AND entity_id = ? AND activity_type = 'LEAD_CREATED'",
            orgId, leadId
        );
    }

    @Test
    void activityLoggedEventUsesActivityTypeFromPayload() throws Exception {
        UUID contactId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("activityType", "CALL");
        payload.put("notes", "left voicemail");

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(), Topics.ACTIVITY_LOGGED, 1, orgId.toString(), "contact",
            contactId.toString(), java.time.Instant.now(), "trace-logged-1", payload
        );
        eventPublisher.publish(Topics.ACTIVITY_LOGGED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        awaitRowCount(
            "SELECT COUNT(*) FROM activities WHERE org_id = ? AND entity_type = 'contact' "
                + "AND entity_id = ? AND activity_type = 'CALL'",
            orgId, contactId
        );
    }

    private void awaitRowCount(String sql, UUID orgId, UUID entityId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Integer count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(sql, Integer.class, orgId, entityId);
            if (count != null && count >= 1) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(count).isEqualTo(1);
    }
}
