package com.salespipe.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import com.salespipe.eventing.consumer.IdempotentConsumer;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.eventing.consumer.InboundEventNormalizer;
import com.salespipe.eventing.consumer.ProcessedEventRepository;
import com.salespipe.eventing.producer.EventPublisher;
import com.salespipe.support.PostgresRedisTestBase;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T4.9 chaos-lite (STRETCH): kill a consumer mid-process and verify NO message loss and NO
 * double-processing. Publishes a burst, stops the listener container partway through the
 * drain (the "kill"), restarts it, and asserts every event is eventually processed exactly
 * once — the guarantee that the transactional outbox + {@code processed_events} idempotency
 * are supposed to give even across a consumer crash.
 */
class ConsumerKillNoLossIT extends PostgresRedisTestBase {

    private static final int BURST = 300;
    private static final String LISTENER_ID = "chaos-recording";
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

    @org.junit.jupiter.api.BeforeAll
    static void startSchemaRegistry() {
        // After the @Container Kafka is up (managed by the Testcontainers extension) — not
        // in a static block, which would run before Kafka and fail to connect.
        schemaRegistry.start();
    }

    @org.junit.jupiter.api.AfterAll
    static void stopSchemaRegistry() {
        schemaRegistry.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.eventing.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.eventing.schema-registry-url",
            () -> "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
    }

    // Produced-only topic in the base app (no real consumer) so only our chaos consumer handles it.
    static final String TOPIC = Topics.LEAD_SCORE_UPDATED;

    @Autowired EventPublisher eventPublisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired ChaosRecordingConsumer consumer;

    @Test
    void killingConsumerMidDrainLosesNoMessagesAndDoesNotDoubleProcess() throws Exception {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, 'Chaos', 'FREE')", orgId);

        // Publish the full burst up front.
        for (int i = 0; i < BURST; i++) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("i", i);
            EventEnvelope event = new EventEnvelope(
                UUID.randomUUID(), TOPIC, 1, orgId.toString(), "lead",
                UUID.randomUUID().toString(), Instant.now(), "trace-chaos", payload);
            eventPublisher.publish(TOPIC, event).get(30, TimeUnit.SECONDS);
        }

        // KILL the consumer as soon as it has started but well before it can finish the
        // 300-event burst, then restart it and let it recover.
        long killDeadline = System.currentTimeMillis() + 10_000;
        while (consumer.distinctHandled() == 0 && System.currentTimeMillis() < killDeadline) {
            Thread.sleep(20);
        }
        registry.getListenerContainer(LISTENER_ID).stop();
        int handledAtKill = consumer.distinctHandled();

        // Restart the consumer — it must pick up from the last committed offset and finish.
        registry.getListenerContainer(LISTENER_ID).start();

        // Every event eventually recorded exactly once.
        long deadline = System.currentTimeMillis() + 90_000;
        while (consumer.distinctHandled() < BURST && System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
        }

        assertThat(handledAtKill)
            .as("consumer was killed before draining the whole burst")
            .isLessThan(BURST);
        assertThat(consumer.distinctHandled()).as("no message lost").isEqualTo(BURST);
        assertThat(consumer.totalHandlerInvocations())
            .as("no double-processing (exactly-once via processed_events)")
            .isEqualTo(BURST);

        Integer processedRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = ?",
            Integer.class, ChaosRecordingConsumer.GROUP);
        assertThat(processedRows).isEqualTo(BURST);
    }

    @TestConfiguration
    static class Config {
        @Bean
        ChaosRecordingConsumer chaosRecordingConsumer(
            InboundEventNormalizer normalizer, ProcessedEventRepository repo,
            DlqPublisher dlqPublisher, TransactionTemplate tx, RetryRegistry retryRegistry
        ) {
            return new ChaosRecordingConsumer(normalizer, repo, dlqPublisher, tx, retryRegistry);
        }
    }

    /** Records every distinct event it handles, plus total handler invocations (to prove exactly-once). */
    static class ChaosRecordingConsumer extends IdempotentConsumer {

        static final String GROUP = "chaos-recording-consumer";

        private final java.util.Set<UUID> distinct = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicInteger invocations =
            new java.util.concurrent.atomic.AtomicInteger();

        ChaosRecordingConsumer(
            InboundEventNormalizer normalizer, ProcessedEventRepository repo,
            DlqPublisher dlqPublisher, TransactionTemplate tx, RetryRegistry retryRegistry
        ) {
            super(normalizer, repo, dlqPublisher, tx, retryRegistry);
        }

        int distinctHandled() { return distinct.size(); }
        int totalHandlerInvocations() { return invocations.get(); }

        @Override
        protected String consumerGroup() { return GROUP; }

        @Override
        protected void handle(InboundEvent event) {
            invocations.incrementAndGet();
            distinct.add(event.eventId());
        }

        @KafkaListener(
            id = LISTENER_ID,
            topics = TOPIC,
            groupId = GROUP,
            containerFactory = "eventKafkaListenerContainerFactory"
        )
        void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
            consume(record, ack);
        }
    }
}
