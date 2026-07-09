package com.salespipe.emailtracking;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.emailtracking.security.TrackingSigner;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.support.PostgresRedisTestBase;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializerConfig;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T2.6 coverage for {@code WebhookController} (SendGrid-shaped bounce ingestion —
 * see that class's javadoc for the provider-shape choice). Not one of the plan's four
 * explicitly-named acceptance tests (those are pixel-open focused), but this endpoint
 * is one of the task's listed deliverable files, so it gets its own smoke test: a
 * bounce event for a known tracking_id maps to a published BOUNCED
 * {@code email.event.received} event.
 */
class WebhookControllerIT extends PostgresRedisTestBase {

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

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrackingSigner trackingSigner;

    UUID orgId;
    UUID emailId;
    UUID leadId;
    UUID trackingId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;

        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Webhook Test Org", "FREE");

        leadId = UUID.randomUUID();
        jdbc.update("INSERT INTO leads (id, org_id, status) VALUES (?, ?, ?)",
            leadId, orgId, "NEW");

        emailId = UUID.randomUUID();
        trackingId = trackingSigner.newTrackingId();
        String sig = trackingSigner.sign(trackingId, orgId);
        jdbc.update(
            "INSERT INTO emails (id, org_id, lead_id, subject, tracking_id, tracking_sig) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            emailId, orgId, leadId, "Webhook Test Subject", trackingId, sig);
    }

    @Test
    void bounceEventForKnownTrackingIdPublishesBouncedEvent() throws Exception {
        List<Map<String, Object>> body = List.of(Map.of(
            "email", "recipient@example.com",
            "event", "bounce",
            "trackingId", trackingId.toString(),
            "ip", "203.0.113.5",
            "useragent", "SendGrid-Event-Webhook"
        ));

        given().contentType(ContentType.JSON).body(body)
            .post("/webhooks/email")
        .then()
            .statusCode(204);

        EventEnvelope received = awaitOwnEvent(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(30));
        assertThat(received.orgId()).isEqualTo(orgId.toString());
        assertThat(received.aggregateType()).isEqualTo("lead");
        assertThat(received.aggregateId()).isEqualTo(leadId.toString());
        assertThat(received.payload().get("eventType").asText()).isEqualTo("BOUNCED");
        assertThat(received.payload().get("emailId").asText()).isEqualTo(emailId.toString());
    }

    @Test
    void unmappedEventTypeIsAcceptedButNotPublished() throws Exception {
        List<Map<String, Object>> body = List.of(Map.of(
            "email", "recipient@example.com",
            "event", "delivered",
            "trackingId", trackingId.toString()
        ));

        given().contentType(ContentType.JSON).body(body)
            .post("/webhooks/email")
        .then()
            .statusCode(204);

        assertNoOwnEventArrives(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(8));
    }

    @Test
    void unknownTrackingIdIsAcceptedButNotPublished() throws Exception {
        List<Map<String, Object>> body = List.of(Map.of(
            "email", "recipient@example.com",
            "event", "bounce",
            "trackingId", UUID.randomUUID().toString()
        ));

        given().contentType(ContentType.JSON).body(body)
            .post("/webhooks/email")
        .then()
            .statusCode(204);

        assertNoOwnEventArrives(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(8));
    }

    private EventEnvelope awaitOwnEvent(String topic, Duration timeout) throws Exception {
        try (KafkaConsumer<String, EventEnvelope> consumer = buildConsumer("webhook-test-")) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, EventEnvelope> record : records) {
                    EventEnvelope value = record.value();
                    if (value != null && value.payload() != null && value.payload().has("emailId")
                            && value.payload().get("emailId").asText().equals(emailId.toString())) {
                        return value;
                    }
                }
            }
            throw new AssertionError("No event for emailId=" + emailId + " arrived on topic " + topic);
        }
    }

    private void assertNoOwnEventArrives(String topic, Duration waitTime) throws Exception {
        try (KafkaConsumer<String, EventEnvelope> consumer = buildConsumer("webhook-negative-test-")) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + waitTime.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, EventEnvelope> record : records) {
                    EventEnvelope value = record.value();
                    if (value != null && value.payload() != null && value.payload().has("emailId")
                            && value.payload().get("emailId").asText().equals(emailId.toString())) {
                        throw new AssertionError("Unexpected event published: " + value);
                    }
                }
            }
        }
    }

    private KafkaConsumer<String, EventEnvelope> buildConsumer(String groupPrefix) {
        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            KafkaJsonSchemaDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
            KafkaJsonSchemaDeserializerConfig.JSON_VALUE_TYPE, EventEnvelope.class.getName()
        );
        StringDeserializer keyDeserializer = new StringDeserializer();
        KafkaJsonSchemaDeserializer<EventEnvelope> valueDeserializer = new KafkaJsonSchemaDeserializer<>();
        keyDeserializer.configure(consumerProps, true);
        valueDeserializer.configure(consumerProps, false);
        return new KafkaConsumer<>(consumerProps, keyDeserializer, valueDeserializer);
    }
}
