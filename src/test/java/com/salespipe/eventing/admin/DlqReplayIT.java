package com.salespipe.eventing.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.eventing.EventingProperties;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T4.5: proves the DLQ replay path end-to-end against a real Kafka — a poison message on a
 * {@code *.DLQ} topic (carrying the {@code x-original-topic} header) can be inspected and
 * replayed back to its source topic.
 */
@Testcontainers
class DlqReplayIT {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private DlqAdminService service() {
        EventingProperties props = new EventingProperties();
        props.setBootstrapServers(kafka.getBootstrapServers());
        return new DlqAdminService(props);
    }

    @Test
    void inspectAndReplayMovesMessageBackToOriginalTopic() throws Exception {
        String originalTopic = Topics.DEAL_STAGE_CHANGED;
        String dlqTopic = Topics.dlqFor(originalTopic);
        String key = "deal-" + UUID.randomUUID();
        byte[] value = ("{\"dealId\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);

        // Seed a poison message onto the DLQ topic, shaped like DlqPublisher writes it.
        try (KafkaProducer<String, byte[]> producer = producer()) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(dlqTopic, key, value);
            record.headers().add(DlqPublisher.HEADER_ORIGINAL_TOPIC,
                originalTopic.getBytes(StandardCharsets.UTF_8));
            record.headers().add(DlqPublisher.HEADER_FAILURE_REASON,
                "java.lang.IllegalStateException: boom".getBytes(StandardCharsets.UTF_8));
            record.headers().add(DlqPublisher.HEADER_ATTEMPTS, "3".getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }

        DlqAdminService service = service();

        // Inspect: the message is listed with its failure metadata.
        List<DlqMessage> listed = service.list(dlqTopic, 10);
        assertThat(listed).anySatisfy(m -> {
            assertThat(m.key()).isEqualTo(key);
            assertThat(m.originalTopic()).isEqualTo(originalTopic);
            assertThat(m.failureReason()).contains("boom");
            assertThat(m.attempts()).isEqualTo("3");
        });
        assertThat(service.count(dlqTopic)).isGreaterThanOrEqualTo(1);

        DlqMessage target = listed.stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();

        // Replay: re-publishes to the original topic.
        String replayedTo = service.replay(target.dlqTopic(), target.partition(), target.offset());
        assertThat(replayedTo).isEqualTo(originalTopic);

        // The original topic now has the replayed record.
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(originalTopic));
            ConsumerRecord<String, byte[]> found = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofSeconds(2))) {
                    if (key.equals(r.key())) {
                        found = r;
                        break;
                    }
                }
            }
            assertThat(found).as("replayed record on original topic").isNotNull();
            assertThat(found.value()).isEqualTo(value);
        }
    }

    private KafkaProducer<String, byte[]> producer() {
        Map<String, Object> p = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(p);
    }

    private KafkaConsumer<String, byte[]> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-replay-verify-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(p);
    }
}
