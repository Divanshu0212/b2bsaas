package com.salespipe.eventing.consumer;

import com.salespipe.eventing.EventingProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared Spring Kafka consumer/producer config for the idempotent-consumer framework
 * (T2.4), layered on the T2.1 scaffolding.
 *
 * <p><b>Deviation from the original T2.1 scaffolding:</b> the consumer factory here is
 * {@code <String, byte[]>} (raw bytes value, no Schema-Registry-bound {@code
 * EventEnvelope} deserializer), not {@code <String, EventEnvelope>}. Two reasons:
 * <ol>
 *   <li>The CDC relay path's Kafka message value is the outbox row's bare {@code
 *       payload} column, NOT an {@code EventEnvelope} document (see {@code
 *       debezium/README.md}'s "Value shape gotcha for future consumer work"); a
 *       schema-registry {@code KafkaJsonSchemaDeserializer} bound to {@code
 *       EventEnvelope.class} would throw on every CDC-relayed message, silently
 *       working only for the direct-produce/polling-relay path.</li>
 *   <li>Even the direct-produce/polling-relay path's value is NOT plain JSON text —
 *       {@link com.salespipe.eventing.producer.ProducerConfig}'s {@code
 *       KafkaJsonSchemaSerializer} writes the Confluent wire format (1 magic byte +
 *       4-byte big-endian schema id, THEN the JSON payload). A plain {@code
 *       StringDeserializer} would include those 5 leading bytes as part of the
 *       "string", producing invalid JSON (a leading {@code 0x00} control byte) on
 *       every parse. Reading {@code byte[]} and stripping that prefix explicitly (see
 *       {@link InboundEventNormalizer}) is the only shape-agnostic way to handle both
 *       the framed (direct-produce/polling) and unframed (CDC, Debezium's plain {@code
 *       JsonConverter} with {@code schemas.enable=false}) wire formats.</li>
 * </ol>
 * {@link IdempotentConsumer} (via {@link InboundEventNormalizer}) parses the raw bytes
 * itself and branches on the presence of the CDC path's {@code id} header, so both
 * shapes normalize into one {@link InboundEvent} before dedupe/handler logic runs.
 *
 * <p>Virtual threads (overview §6.3, "Java 21 virtual threads for I/O-bound
 * consumers"): the listener container factory's task executor is a {@link
 * TaskExecutorAdapter} around {@link Executors#newVirtualThreadPerTaskExecutor()}. This
 * is the executor Spring Kafka uses per-consumer-thread inside the container (distinct
 * from {@code spring.threads.virtual.enabled}, which only affects the embedded web
 * server's Tomcat request-handling threads and has no effect on Kafka listener
 * containers).
 */
@Configuration
@EnableConfigurationProperties(EventingProperties.class)
public class EventConsumerConfig {

    @Bean
    public ConsumerFactory<String, byte[]> eventConsumerFactory(EventingProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> eventKafkaListenerContainerFactory(
        ConsumerFactory<String, byte[]> eventConsumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Virtual-thread-per-task executor for the listener's consumer thread(s) — I/O-bound
        // (DB writes, downstream calls) consumers per overview §6.3.
        factory.getContainerProperties().setListenerTaskExecutor(
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        return factory;
    }

    /**
     * Plain {@code byte[]}-valued producer, used by {@link DlqPublisher} to republish
     * the original raw record bytes/value to {@code <topic>.DLQ} completely unchanged
     * (no re-encoding, no Schema-Registry envelope framing added or removed — see
     * {@link DlqPublisher} javadoc).
     */
    @Bean
    public ProducerFactory<String, byte[]> dlqProducerFactory(EventingProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, byte[]> dlqKafkaTemplate(ProducerFactory<String, byte[]> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    /**
     * Spring Boot's {@code TransactionAutoConfiguration} registers a {@link
     * PlatformTransactionManager} but not a {@link TransactionTemplate} — {@link
     * IdempotentConsumer} needs the latter to run the dedupe-insert + handler as one
     * programmatic transaction and to explicitly control commit/rollback per outcome
     * (duplicate vs. handled vs. handler failure) via {@link
     * org.springframework.transaction.TransactionStatus#setRollbackOnly()} — see
     * {@link IdempotentConsumer}'s class javadoc for the full per-outcome breakdown.
     *
     * <p>{@code @ConditionalOnBean(PlatformTransactionManager.class)}: narrow test
     * slices that load this configuration class without JPA/DataSource
     * auto-configuration (e.g. {@code EventingSmokeIT}, which loads {@code
     * EventConsumerConfig} for its consumer-factory beans but has no database) would
     * otherwise fail to start with an unsatisfied-dependency error — this bean (and by
     * extension, {@link IdempotentConsumer} itself, which is never instantiated in
     * those slices anyway since they declare no concrete subclass) is simply skipped
     * when there's no transaction manager to wrap.
     */
    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
