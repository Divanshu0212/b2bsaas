package com.salespipe.emailtracking;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.emailtracking.security.TrackingProperties;
import com.salespipe.emailtracking.security.TrackingSigner;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.support.PostgresRedisTestBase;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializerConfig;
import io.restassured.RestAssured;
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
 * T2.6 acceptance tests for {@code TrackingController} (open pixel + link click),
 * exercised against real Postgres (schema from the actual V5__email_tracking.sql via
 * Flyway, through {@link PostgresRedisTestBase}), real Redis (Bucket4j rate limiting),
 * and real Kafka + Schema Registry (asserting the {@code email.event.received} event
 * actually round-trips) — same layered-container pattern as {@code IdempotentConsumerIT}
 * (Postgres+Redis from the base class, Kafka+SchemaRegistry added here).
 *
 * <p>Covers the plan's three tracking-endpoint acceptance criteria: (1) valid pixel
 * request returns the gif and an OPENED event is published with correct fields; (2) a
 * tampered signature is rejected before any write/emit; (3) rate limiting trips under
 * burst (small budget configured via {@code application-test.yml}: capacity 5).
 */
class TrackingControllerIT extends PostgresRedisTestBase {

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
    @Autowired TrackingProperties trackingProperties;

    UUID orgId;
    UUID emailId;
    UUID leadId;
    UUID trackingId;
    String validSig;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;

        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            orgId, "Tracking Test Org", "FREE");

        leadId = UUID.randomUUID();
        jdbc.update("INSERT INTO leads (id, org_id, status) VALUES (?, ?, ?)",
            leadId, orgId, "NEW");

        emailId = UUID.randomUUID();
        trackingId = trackingSigner.newTrackingId();
        validSig = trackingSigner.sign(trackingId, orgId);
        jdbc.update(
            "INSERT INTO emails (id, org_id, lead_id, subject, tracking_id, tracking_sig) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            emailId, orgId, leadId, "Test Subject", trackingId, validSig);
    }

    @Test
    void validPixelRequestReturnsGifAndPublishesOpenedEvent() throws Exception {
        given()
            .get("/emails/" + trackingId + "/open?sig=" + validSig)
        .then()
            .statusCode(200)
            .contentType("image/gif");

        EventEnvelope received = awaitOneEvent(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(30));

        assertThat(received.eventType()).isEqualTo(Topics.EMAIL_EVENT_RECEIVED);
        assertThat(received.orgId()).isEqualTo(orgId.toString());
        assertThat(received.aggregateType()).isEqualTo("lead");
        assertThat(received.aggregateId()).isEqualTo(leadId.toString());
        assertThat(received.payload().get("emailId").asText()).isEqualTo(emailId.toString());
        assertThat(received.payload().get("leadId").asText()).isEqualTo(leadId.toString());
        assertThat(received.payload().get("eventType").asText()).isEqualTo("OPENED");
    }

    @Test
    void tamperedSignatureIsRejectedWithNoWriteAndNoEvent() throws Exception {
        // Tamper a character in the middle of the signature, not the last one: base64
        // (32-byte HMAC-SHA256 output, 43 chars, no padding) has 2 unused/don't-care
        // bits in its final character, so some last-character substitutions decode to
        // byte-identical signatures and wouldn't actually exercise "tampered sig
        // rejected" at all. A middle-character flip always changes the decoded bytes.
        int mid = validSig.length() / 2;
        char midChar = validSig.charAt(mid);
        char replacement = midChar == 'A' ? 'B' : 'A';
        String tamperedSig = validSig.substring(0, mid) + replacement + validSig.substring(mid + 1);

        given()
            .get("/emails/" + trackingId + "/open?sig=" + tamperedSig)
        .then()
            .statusCode(204);

        assertNoEventArrives(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(8));

        Integer eventRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM email_events WHERE email_id = ?", Integer.class, emailId);
        assertThat(eventRows).isZero();
    }

    @Test
    void unknownTrackingIdIsRejectedWithNoWriteAndNoEvent() throws Exception {
        UUID unknownTrackingId = UUID.randomUUID();
        String someSig = trackingSigner.sign(unknownTrackingId, orgId);

        given()
            .get("/emails/" + unknownTrackingId + "/open?sig=" + someSig)
        .then()
            .statusCode(204);

        assertNoEventArrives(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(8));
    }

    @Test
    void validClickRedirectsAndPublishesClickedEvent() throws Exception {
        given()
            .redirects().follow(false)
            .get("/emails/" + trackingId + "/click?sig=" + validSig + "&url=https://example.com/landing")
        .then()
            .statusCode(302)
            .header("Location", "https://example.com/landing");

        EventEnvelope received = awaitOneEvent(Topics.EMAIL_EVENT_RECEIVED, Duration.ofSeconds(30));
        assertThat(received.payload().get("eventType").asText()).isEqualTo("CLICKED");
    }

    @Test
    void clickRejectsUnsafeRedirectScheme() {
        given()
            .redirects().follow(false)
            .get("/emails/" + trackingId + "/click?sig=" + validSig + "&url=javascript:alert(1)")
        .then()
            .statusCode(400);
    }

    @Test
    void rateLimitTripsUnderBurst() {
        // application-test.yml configures a capacity of 5 for this profile — hit the
        // pixel endpoint capacity+1 times with fresh (but always-valid) emails each
        // time so every request passes signature validation and only the rate limiter
        // can be responsible for a non-200/204 outcome. The pixel is still served (200)
        // even once the budget is exhausted per TrackingController's design (rate
        // limiting gates event emission, not pixel rendering) — so what we assert here
        // is the effect the plan's acceptance criterion actually cares about: the
        // (N+1)th request's event is dropped by the limiter, i.e. fewer events reach
        // Kafka than requests were made.
        int capacity = trackingProperties.getRateLimitCapacity();
        int totalRequests = capacity + 3;
        java.util.Set<String> burstEmailIds = new java.util.HashSet<>();

        for (int i = 0; i < totalRequests; i++) {
            UUID tid = trackingSigner.newTrackingId();
            String sig = trackingSigner.sign(tid, orgId);
            UUID eid = UUID.randomUUID();
            burstEmailIds.add(eid.toString());
            jdbc.update(
                "INSERT INTO emails (id, org_id, lead_id, subject, tracking_id, tracking_sig) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                eid, orgId, leadId, "Burst " + i, tid, sig);

            given()
                .get("/emails/" + tid + "/open?sig=" + sig)
            .then()
                .statusCode(200);
        }

        int received = countEvents(Topics.EMAIL_EVENT_RECEIVED, burstEmailIds, Duration.ofSeconds(15));
        assertThat(received).isLessThan(totalRequests);
        assertThat(received).isLessThanOrEqualTo(capacity);
    }

    private EventEnvelope awaitOneEvent(String topic, Duration timeout) throws Exception {
        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "tracking-test-" + UUID.randomUUID(),
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
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            // Filter by this test's own emailId: other tests in this class (and
            // whichever order JUnit picks) share the same topic with no per-test
            // isolation, so earlier tests' leftover records are visible to a
            // fresh-group "earliest" consumer too. Only accept the record that's
            // actually this test's own event.
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
            throw new AssertionError("No event for emailId=" + emailId + " arrived on topic " + topic
                + " within " + timeout);
        }
    }

    private void assertNoEventArrives(String topic, Duration waitTime) throws Exception {
        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "tracking-negative-test-" + UUID.randomUUID(),
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
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, EventEnvelope> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + waitTime.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> polled = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, EventEnvelope> r : polled) {
                    // Only fail on an event that could plausibly be from THIS test's
                    // email/tracking_id — other concurrently-running tests in this
                    // class share the topic (no per-test topic isolation), so filter
                    // by our own emailId before asserting.
                    if (r.value() != null && r.value().payload() != null
                            && r.value().payload().has("emailId")
                            && r.value().payload().get("emailId").asText().equals(emailId.toString())) {
                        throw new AssertionError("Unexpected event published for tampered/unknown request: " + r.value());
                    }
                }
            }
        }
    }

    private int countEvents(String topic, java.util.Set<String> ownEmailIds, Duration timeout) {
        String schemaRegistryUrl = "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "tracking-burst-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            KafkaJsonSchemaDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
            KafkaJsonSchemaDeserializerConfig.JSON_VALUE_TYPE, EventEnvelope.class.getName()
        );
        StringDeserializer keyDeserializer = new StringDeserializer();
        KafkaJsonSchemaDeserializer<EventEnvelope> valueDeserializer = new KafkaJsonSchemaDeserializer<>();
        keyDeserializer.configure(consumerProps, true);
        valueDeserializer.configure(consumerProps, false);

        int maxExpected = ownEmailIds.size();
        int count = 0;
        try (KafkaConsumer<String, EventEnvelope> consumer = new KafkaConsumer<>(
                consumerProps, keyDeserializer, valueDeserializer)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            long lastProgress = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline && count < maxExpected) {
                ConsumerRecords<String, EventEnvelope> polled = consumer.poll(Duration.ofSeconds(2));
                boolean progressed = false;
                for (ConsumerRecord<String, EventEnvelope> record : polled) {
                    EventEnvelope value = record.value();
                    // Only count events from emails this test created — other tests in
                    // this class share the same topic with no per-test isolation.
                    if (value != null && value.payload() != null && value.payload().has("emailId")
                            && ownEmailIds.contains(value.payload().get("emailId").asText())) {
                        count++;
                        progressed = true;
                    }
                }
                if (progressed) {
                    lastProgress = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgress > 6000) {
                    // No new (relevant) records for a while and we haven't hit
                    // maxExpected — assume the rest were legitimately dropped by the
                    // rate limiter.
                    break;
                }
            }
        }
        return count;
    }
}
