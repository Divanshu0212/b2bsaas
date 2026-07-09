package com.salespipe.eventing.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.eventing.producer.ProducerConfig;
import com.salespipe.eventing.schema.SchemaRegistrationRunner;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T2.3 acceptance test for the polling-relay fallback: proves that toggling to the
 * fallback profile still delivers, per the plan's accept criterion — "Toggling to
 * fallback profile still delivers." Exercises the actual production {@link PollingRelay}
 * bean end-to-end (real Postgres via JdbcTemplate, real Kafka + Schema Registry via
 * {@link EventPublisher}), not a mock.
 *
 * <p>Loads only the eventing-module beans needed (mirrors {@link
 * com.salespipe.eventing.EventingSmokeIT}'s narrow {@code @SpringBootTest(classes=...)}
 * style) plus a minimal JDBC slice for {@link PollingRelay}'s {@link JdbcTemplate}
 * dependency — no full application context/security/JPA required for this focused test.
 */
@SpringBootTest(classes = {
    DataSourceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    ProducerConfig.class,
    SchemaRegistrationRunner.class,
    EventPublisher.class,
    PollingRelay.class
})
@ActiveProfiles("test")
@Testcontainers
class PollingRelayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.eventing.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.eventing.schema-registry-url",
            () -> "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
        // Relay bean is @ConditionalOnProperty; this focused test loads it explicitly
        // via @SpringBootTest(classes=...) regardless, but set the flag too so behavior
        // matches what a real `--spring.profiles.active=polling` run would wire up.
        registry.add("app.eventing.relay-mode", () -> "polling");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PollingRelay relay;

    UUID orgId;

    @BeforeEach
    void setUp() {
        // Minimal schema slice needed for this test's write path — this narrow test
        // context doesn't load Flyway/full app schema (see class javadoc: only the
        // eventing-module beans + a JDBC slice are loaded), so create just what
        // PollingRelay's query touches, matching V2__outbox.sql + V3__ column exactly.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS organizations (
                id UUID PRIMARY KEY, name TEXT, plan TEXT, created_at TIMESTAMPTZ DEFAULT now()
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS outbox_events (
                id             UUID PRIMARY KEY,
                org_id         UUID NOT NULL REFERENCES organizations(id),
                aggregate_type TEXT NOT NULL,
                aggregate_id   TEXT NOT NULL,
                event_type     TEXT NOT NULL,
                payload        JSONB NOT NULL,
                trace_id       TEXT,
                created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                published      BOOLEAN NOT NULL DEFAULT false
            )
            """);
        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Polling Relay Test Org", "FREE");
    }

    @Test
    void relayPublishesUnpublishedRowAndMarksItPublished() throws Exception {
        UUID eventId = UUID.randomUUID();
        String dealId = "deal-poll-1";
        jdbc.update(
            "INSERT INTO outbox_events (id, org_id, aggregate_type, aggregate_id, event_type, payload, trace_id) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            eventId, orgId, "deal", dealId, Topics.DEAL_STAGE_CHANGED,
            "{\"dealId\":\"" + dealId + "\",\"toStageId\":\"stage-1\"}", "trace-poll-1"
        );

        // Trigger the relay method directly rather than waiting on @Scheduled's fixed
        // delay — deterministic, and this is the same production method @Scheduled
        // calls (see PollingRelay#relay).
        relay.relay();

        Boolean published = jdbc.queryForObject(
            "SELECT published FROM outbox_events WHERE id = ?", Boolean.class, eventId);
        assertThat(published).isTrue();

        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "polling-relay-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            KafkaJsonSchemaDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
            KafkaJsonSchemaDeserializerConfig.JSON_VALUE_TYPE, EventEnvelope.class.getName()
        );

        StringDeserializer keyDeserializer = new StringDeserializer();
        KafkaJsonSchemaDeserializer<EventEnvelope> valueDeserializer = new KafkaJsonSchemaDeserializer<>();
        keyDeserializer.configure(consumerProps, true);
        valueDeserializer.configure(consumerProps, false);

        try (KafkaConsumer<String, EventEnvelope> consumer = new KafkaConsumer<>(
                consumerProps, keyDeserializer, valueDeserializer)) {
            consumer.subscribe(List.of(Topics.DEAL_STAGE_CHANGED));

            ConsumerRecords<String, EventEnvelope> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofSeconds(2));
            }

            assertThat(records.count()).isEqualTo(1);
            ConsumerRecord<String, EventEnvelope> record = records.iterator().next();

            assertThat(record.key()).isEqualTo(dealId);
            EventEnvelope received = record.value();
            assertThat(received.eventId()).isEqualTo(eventId);
            assertThat(received.eventType()).isEqualTo(Topics.DEAL_STAGE_CHANGED);
            assertThat(received.orgId()).isEqualTo(orgId.toString());
            assertThat(received.aggregateType()).isEqualTo("deal");
            assertThat(received.aggregateId()).isEqualTo(dealId);
            assertThat(received.traceId()).isEqualTo("trace-poll-1");
            assertThat(received.payload().get("dealId").asText()).isEqualTo(dealId);
        }
    }

    @Test
    void relayLeavesAlreadyPublishedRowsAlone() {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO outbox_events (id, org_id, aggregate_type, aggregate_id, event_type, payload, published) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, true)",
            eventId, orgId, "deal", "deal-already-done", Topics.DEAL_STAGE_CHANGED,
            "{\"dealId\":\"deal-already-done\",\"toStageId\":\"stage-1\"}"
        );

        relay.relay();

        Boolean published = jdbc.queryForObject(
            "SELECT published FROM outbox_events WHERE id = ?", Boolean.class, eventId);
        assertThat(published).isTrue();
    }
}
