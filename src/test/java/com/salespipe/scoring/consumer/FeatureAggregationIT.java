package com.salespipe.scoring.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.scoring.client.ScoreRequest;
import com.salespipe.scoring.client.ScoreResponse;
import com.salespipe.scoring.client.ScoringClient;
import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * T3.1 accept: "an email OPENED event increments email_open_count exactly once even on
 * redelivery; updated_at moves." Publishes the SAME {@code email.event.received} envelope
 * twice (identical event_id = redelivery) and asserts the counter lands at exactly 1 —
 * the idempotent-consumer dedupe row makes the second delivery a no-op (overview change
 * #3: feature writes are consistency-managed, not a silent dual-write).
 *
 * <p>{@link ScoringClient} is mocked so the recompute the consumer triggers doesn't need
 * a live Python service — this test targets feature aggregation, not scoring.
 */
class FeatureAggregationIT extends PostgresRedisTestBase {

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
    }

    @MockitoBean ScoringClient scoringClient;

    @Autowired JdbcTemplate jdbc;
    @Autowired EventPublisher eventPublisher;
    @Autowired ObjectMapper objectMapper;

    UUID orgId;
    UUID leadId;

    @BeforeEach
    void setUp() {
        // Recompute after every feature update must not need a live ML service.
        Mockito.when(scoringClient.score(ArgumentMatchers.any(ScoreRequest.class)))
            .thenReturn(Optional.<ScoreResponse>empty());

        orgId = UUID.randomUUID();
        leadId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Feature Agg Test Org", "FREE");
        jdbc.update("INSERT INTO leads (id, org_id, status, version) VALUES (?, ?, 'NEW', 0)",
            leadId, orgId);
    }

    @Test
    void emailOpenedIncrementsExactlyOnceOnRedelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("emailId", UUID.randomUUID().toString());
        payload.put("leadId", leadId.toString());
        payload.put("eventType", "OPENED");

        // Same event_id twice = redelivery of one logical event.
        EventEnvelope event = new EventEnvelope(
            eventId, Topics.EMAIL_EVENT_RECEIVED, 1, orgId.toString(), "lead",
            leadId.toString(), Instant.now(), "trace-email-1", payload);
        eventPublisher.publish(Topics.EMAIL_EVENT_RECEIVED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);
        eventPublisher.publish(Topics.EMAIL_EVENT_RECEIVED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        awaitOpenCount(1);
    }

    private void awaitOpenCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Integer count = null;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(
                "SELECT email_open_count FROM lead_features WHERE org_id = ? AND lead_id = ?",
                Integer.class, orgId, leadId);
            if (count != null && count >= expected) {
                break;
            }
            Thread.sleep(300);
        }
        // Give a redelivery a chance to (wrongly) double-count before asserting exactly-once.
        Thread.sleep(1000);
        count = jdbc.queryForObject(
            "SELECT email_open_count FROM lead_features WHERE org_id = ? AND lead_id = ?",
            Integer.class, orgId, leadId);
        assertThat(count).isEqualTo(expected);
    }
}
