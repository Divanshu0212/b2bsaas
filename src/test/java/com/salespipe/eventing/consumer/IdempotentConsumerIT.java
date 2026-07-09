package com.salespipe.eventing.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T2.4 acceptance tests for {@link IdempotentConsumer}, exercised against real Kafka +
 * Schema Registry + Postgres (Testcontainers), on top of the full application context
 * (Flyway-migrated schema, so {@code processed_events}/{@code outbox_events} come from
 * the real {@code V2__outbox.sql} — no hand-rolled test schema needed) — same pattern
 * as {@link com.salespipe.eventing.EventingSmokeIT} / {@link
 * com.salespipe.eventing.outbox.PollingRelayIT}, extended to a full context because
 * {@link ProcessedEventRepository}/{@link IdempotentConsumer} need JPA + a real
 * {@code PlatformTransactionManager} + Resilience4j's auto-configured {@code
 * RetryRegistry}.
 *
 * <p>Covers both relay shapes documented on {@link InboundEvent}: a direct-produce
 * {@link EventEnvelope} JSON value (what {@link EventPublisher}/the polling relay
 * emit) and a CDC-shaped raw-payload value with an {@code id} header (what Debezium's
 * outbox-router SMT emits) — see {@code debezium/README.md}.
 */
@Import({IdempotentConsumerIT.RecordingConsumer.class, IdempotentConsumerIT.AlwaysFailingConsumer.class})
class IdempotentConsumerIT extends PostgresRedisTestBase {

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
        // Tight retry loop for the poison-message test so it doesn't take the default
        // backoff schedule's real wall-clock time.
        registry.add("resilience4j.retry.instances.idempotentConsumer.max-attempts", () -> "2");
        registry.add("resilience4j.retry.instances.idempotentConsumer.wait-duration", () -> "50ms");
    }

    // Real topics with schemas already registered (KafkaJsonSchemaSerializer requires a
    // pre-registered schema; AUTO_REGISTER_SCHEMAS=false), but deliberately picked as
    // ones with NO production consumer: this IT boots the full application context (see
    // class javadoc), so a topic like deal.stage.changed would also be consumed by real
    // IdempotentConsumer subclasses (activity/notification) whose handlers would
    // race/interfere with this test's own poison/recording consumers. lead.score.updated
    // and email.event.received are produced-only in Phase 2 (scoring consumer is Phase 3).
    static final String OK_TOPIC = Topics.LEAD_SCORE_UPDATED;
    static final String POISON_TOPIC = Topics.EMAIL_EVENT_RECEIVED;

    @Autowired JdbcTemplate jdbc;
    @Autowired EventPublisher eventPublisher;
    @Autowired RecordingConsumer recordingConsumer;
    @Autowired AlwaysFailingConsumer poisonConsumer;
    @Autowired ObjectMapper objectMapper;

    UUID orgId;

    void setUpOrg() {
        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            orgId, "Idempotent Consumer Test Org", "FREE");
    }

    @Test
    void redeliveringTheSameEventRunsTheHandlerOnce() throws Exception {
        setUpOrg();
        recordingConsumer.reset();

        UUID eventId = UUID.randomUUID();
        String leadId = "lead-idem-1";
        EventEnvelope event = new EventEnvelope(
            eventId, Topics.LEAD_CREATED, 1, orgId.toString(), "lead", leadId,
            java.time.Instant.now(), "trace-idem-1", samplePayload(leadId)
        );

        // First delivery.
        eventPublisher.publish(OK_TOPIC, event).get(30, TimeUnit.SECONDS);
        assertThat(recordingConsumer.awaitCount(1, Duration.ofSeconds(30))).isTrue();

        // Simulate redelivery of the exact same event_id — republish the identical
        // envelope. A real redelivery (e.g. consumer group rebalance replaying
        // un-acked offsets) would arrive as the same Kafka record; republishing an
        // envelope with the same eventId is the documented-equivalent way to simulate
        // that without fighting consumer-group offset mechanics in a test.
        eventPublisher.publish(OK_TOPIC, event).get(30, TimeUnit.SECONDS);

        // Give the second delivery time to reach the consumer and be skipped; then
        // assert the handler count never moved past 1.
        Thread.sleep(3000);
        assertThat(recordingConsumer.handledCount()).isEqualTo(1);

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
            Integer.class, RecordingConsumer.GROUP, eventId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void cdcShapedMessageWithIdHeaderIsHandledAndDeduped() throws Exception {
        setUpOrg();
        recordingConsumer.reset();

        UUID eventId = UUID.randomUUID();
        String leadId = "lead-idem-cdc-1";
        ObjectNode payload = samplePayload(leadId);

        publishCdcShaped(OK_TOPIC, eventId, leadId, payload);
        assertThat(recordingConsumer.awaitCount(1, Duration.ofSeconds(30))).isTrue();

        // Redeliver the identical CDC-shaped record (same id header, same key/value).
        publishCdcShaped(OK_TOPIC, eventId, leadId, payload);
        Thread.sleep(3000);
        assertThat(recordingConsumer.handledCount()).isEqualTo(1);

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
            Integer.class, RecordingConsumer.GROUP, eventId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void poisonMessageLandsInDlqWithFailureReasonAfterRetriesExhausted() throws Exception {
        setUpOrg();
        poisonConsumer.reset();

        UUID eventId = UUID.randomUUID();
        String dealId = "deal-poison-1";
        EventEnvelope event = new EventEnvelope(
            eventId, Topics.DEAL_STAGE_CHANGED, 1, orgId.toString(), "deal", dealId,
            java.time.Instant.now(), "trace-poison-1", samplePayload(dealId)
        );

        eventPublisher.publish(POISON_TOPIC, event).get(30, TimeUnit.SECONDS);

        // Wait for retries to exhaust (max-attempts=2, wait-duration=50ms per the
        // DynamicPropertySource override above) then check the DLQ topic.
        assertThat(poisonConsumer.awaitAttempts(2, Duration.ofSeconds(30))).isTrue();

        // POISON_TOPIC (EMAIL_EVENT_RECEIVED) is also consumed by the real
        // EmailFeatureConsumer, which fails this synthetic payload with its own
        // "Invalid UUID" error and DLQs it too — same DLQ topic, different consumer
        // group. So don't grab the first DLQ record; find the one this test's
        // AlwaysFailingConsumer produced (its failure reason carries "boom").
        String dlqTopic = Topics.dlqFor(POISON_TOPIC);
        ConsumerRecord<String, byte[]> dlqRecord = consumeMatching(dlqTopic,
            r -> dealId.equals(r.key())
                && headerValue(r, DlqPublisher.HEADER_FAILURE_REASON) != null
                && headerValue(r, DlqPublisher.HEADER_FAILURE_REASON).contains("boom"));

        assertThat(dlqRecord).as("DLQ record from the poison consumer (reason contains 'boom')").isNotNull();
        assertThat(dlqRecord.key()).isEqualTo(dealId);
        String reasonHeader = headerValue(dlqRecord, DlqPublisher.HEADER_FAILURE_REASON);
        assertThat(reasonHeader).isNotBlank();
        assertThat(reasonHeader).contains("boom");
        String attemptsHeader = headerValue(dlqRecord, DlqPublisher.HEADER_ATTEMPTS);
        assertThat(attemptsHeader).isEqualTo("2");

        // Poison message must NOT be recorded as processed.
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
            Integer.class, AlwaysFailingConsumer.GROUP, eventId);
        assertThat(rows).isEqualTo(0);
    }

    private ObjectNode samplePayload(String id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        return node;
    }

    private void publishCdcShaped(String topic, UUID eventId, String key, ObjectNode payload) throws Exception {
        Map<String, Object> producerProps = Map.of(
            org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class,
            org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class
        );
        try (org.apache.kafka.clients.producer.KafkaProducer<String, String> producer =
                 new org.apache.kafka.clients.producer.KafkaProducer<>(producerProps)) {
            org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key, objectMapper.writeValueAsString(payload));
            record.headers().add(new org.apache.kafka.common.header.internals.RecordHeader(
                "id", eventId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            record.headers().add(new org.apache.kafka.common.header.internals.RecordHeader(
                "aggregateType", "lead".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            record.headers().add(new org.apache.kafka.common.header.internals.RecordHeader(
                "orgId", orgId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            producer.send(record).get(30, TimeUnit.SECONDS);
        }
    }

    private ConsumerRecord<String, byte[]> consumeOne(String topic) {
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                consumerProps, new StringDeserializer(), new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, byte[]> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofSeconds(2));
            }
            if (records.isEmpty()) {
                return null;
            }
            return records.iterator().next();
        }
    }

    /**
     * Polls {@code topic} until a record satisfying {@code predicate} is seen (or the
     * 30s deadline passes). Needed where more than one consumer group can land records
     * on the same DLQ topic and the test only cares about one of them.
     */
    private ConsumerRecord<String, byte[]> consumeMatching(
        String topic, java.util.function.Predicate<ConsumerRecord<String, byte[]>> predicate
    ) {
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                consumerProps, new StringDeserializer(), new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofSeconds(2))) {
                    if (predicate.test(r)) {
                        return r;
                    }
                }
            }
            return null;
        }
    }

    private static String headerValue(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- test consumers -----------------------------------------------------------

    /**
     * Concrete {@link IdempotentConsumer} whose handler just records that it ran.
     * Registered via {@code @Import} on the test class rather than component-scanned —
     * same reasoning as {@code OutboxAtomicityIT.TransactionalWriter}: a nested test
     * class, not part of any {@code @ApplicationModule} package.
     */
    static class RecordingConsumer extends IdempotentConsumer {

        static final String GROUP = "test-recording-consumer";

        private final AtomicInteger handled = new AtomicInteger();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        RecordingConsumer(
            InboundEventNormalizer normalizer,
            ProcessedEventRepository repo,
            DlqPublisher dlqPublisher,
            org.springframework.transaction.support.TransactionTemplate tx,
            io.github.resilience4j.retry.RetryRegistry retryRegistry
        ) {
            super(normalizer, repo, dlqPublisher, tx, retryRegistry);
        }

        void reset() {
            handled.set(0);
            latch = new CountDownLatch(1);
        }

        int handledCount() {
            return handled.get();
        }

        /** Waits until at least {@code expected} handler invocations have occurred. */
        boolean awaitCount(int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (handled.get() >= expected) {
                    return true;
                }
                latch.await(100, TimeUnit.MILLISECONDS);
            }
            return handled.get() >= expected;
        }

        @Override
        protected String consumerGroup() {
            return GROUP;
        }

        @Override
        protected void handle(InboundEvent event) {
            handled.incrementAndGet();
            latch.countDown();
        }

        @KafkaListener(topics = OK_TOPIC, groupId = GROUP, containerFactory = "eventKafkaListenerContainerFactory")
        void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
            consume(record, ack);
        }
    }

    /** Concrete {@link IdempotentConsumer} whose handler always throws — poison-message DLQ test. */
    static class AlwaysFailingConsumer extends IdempotentConsumer {

        static final String GROUP = "test-always-failing-consumer";

        private final AtomicInteger attempts = new AtomicInteger();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        AlwaysFailingConsumer(
            InboundEventNormalizer normalizer,
            ProcessedEventRepository repo,
            DlqPublisher dlqPublisher,
            org.springframework.transaction.support.TransactionTemplate tx,
            io.github.resilience4j.retry.RetryRegistry retryRegistry
        ) {
            super(normalizer, repo, dlqPublisher, tx, retryRegistry);
        }

        void reset() {
            attempts.set(0);
            latch = new CountDownLatch(1);
        }

        /** Waits until at least {@code expected} handler attempts have occurred. */
        boolean awaitAttempts(int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (attempts.get() >= expected) {
                    return true;
                }
                latch.await(100, TimeUnit.MILLISECONDS);
            }
            return attempts.get() >= expected;
        }

        @Override
        protected String consumerGroup() {
            return GROUP;
        }

        @Override
        protected void handle(InboundEvent event) throws Exception {
            int n = attempts.incrementAndGet();
            throw new IllegalStateException("boom (attempt " + n + ")");
        }

        @KafkaListener(topics = POISON_TOPIC, groupId = GROUP, containerFactory = "eventKafkaListenerContainerFactory")
        void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
            consume(record, ack);
        }
    }
}
