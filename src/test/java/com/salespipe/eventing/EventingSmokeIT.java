package com.salespipe.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T2.1 smoke test: round-trips one {@link EventEnvelope} through a real Kafka topic
 * with Schema Registry validation, using the actual production {@link EventPublisher}
 * / {@link ProducerConfig} beans. Only the eventing module's config classes are loaded
 * (no full app context needed for this wiring-level check).
 */
@SpringBootTest(classes = {
    ProducerConfig.class,
    com.salespipe.eventing.consumer.EventConsumerConfig.class,
    SchemaRegistrationRunner.class,
    EventPublisher.class
})
@ActiveProfiles("test")
@Testcontainers
class EventingSmokeIT {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
        .withNetwork(NETWORK)
        .withNetworkAliases("kafka")
        // Extra internal listener (distinct port — 9092 is already the default PLAINTEXT
        // listener) so schema-registry (a sibling container on the same custom network)
        // can reach the broker via the "kafka" alias; the container's default host-mapped
        // listener (used by the test JVM below) stays intact.
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
        // Kafka container is started by @Container/@Testcontainers before this runs;
        // schema-registry needs the broker reachable first, so start it explicitly here.
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

    @Autowired
    EventPublisher eventPublisher;

    @Test
    void publishedEventRoundTripsThroughKafkaWithSchemaValidation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("leadId", "lead-123");
        payload.put("contactId", "contact-456");
        payload.put("source", "WEBSITE");

        EventEnvelope event = EventEnvelope.of(
            Topics.LEAD_CREATED,
            1,
            "org-789",
            "lead",
            "lead-123",
            "trace-abc",
            payload
        );

        eventPublisher.publish(Topics.LEAD_CREATED, event).get(30, java.util.concurrent.TimeUnit.SECONDS);

        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);

        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "smoke-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            KafkaJsonSchemaDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
            KafkaJsonSchemaDeserializerConfig.JSON_VALUE_TYPE, EventEnvelope.class.getName()
        );

        // KafkaConsumer does NOT call configure() on deserializer instances passed in
        // directly (see its Javadoc) — configure them explicitly first.
        StringDeserializer keyDeserializer = new StringDeserializer();
        KafkaJsonSchemaDeserializer<EventEnvelope> valueDeserializer = new KafkaJsonSchemaDeserializer<>();
        keyDeserializer.configure(consumerProps, true);
        valueDeserializer.configure(consumerProps, false);

        try (KafkaConsumer<String, EventEnvelope> consumer = new KafkaConsumer<>(
                consumerProps, keyDeserializer, valueDeserializer)) {
            consumer.subscribe(List.of(Topics.LEAD_CREATED));

            ConsumerRecords<String, EventEnvelope> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofSeconds(2));
            }

            assertThat(records.count()).isEqualTo(1);
            ConsumerRecord<String, EventEnvelope> record = records.iterator().next();

            assertThat(record.key()).isEqualTo("lead-123");
            EventEnvelope received = record.value();
            assertThat(received.eventId()).isEqualTo(event.eventId());
            assertThat(received.eventType()).isEqualTo(Topics.LEAD_CREATED);
            assertThat(received.orgId()).isEqualTo("org-789");
            assertThat(received.aggregateType()).isEqualTo("lead");
            assertThat(received.aggregateId()).isEqualTo("lead-123");
            assertThat(received.payload().get("leadId").asText()).isEqualTo("lead-123");
        }
    }
}
