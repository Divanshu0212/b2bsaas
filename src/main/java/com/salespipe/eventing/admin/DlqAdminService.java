package com.salespipe.eventing.admin;

import com.salespipe.eventing.EventingProperties;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DLQ inspection + replay (T4.5). Reads the {@code *.DLQ} topics with a short-lived manual
 * {@link KafkaConsumer} (no auto-commit, seek-to-beginning) so listing never disturbs a
 * real consumer group's offsets, and replays a chosen record back to its original topic
 * (the {@code x-original-topic} header stamped by {@link DlqPublisher}). Replay commits the
 * DLQ offset past the record so it isn't replayed twice.
 *
 * <p>Not wired as {@code @KafkaListener} beans — this is on-demand admin tooling driven by
 * {@link DlqAdminController}, so it opens/closes its own clients per call rather than
 * holding long-lived consumers.
 */
@Service
public class DlqAdminService {

    private static final Logger log = LoggerFactory.getLogger(DlqAdminService.class);
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final String ADMIN_GROUP = "dlq-admin";

    private final EventingProperties properties;

    public DlqAdminService(EventingProperties properties) {
        this.properties = properties;
    }

    /** All configured DLQ topic names (one per source topic in {@link Topics#ALL}). */
    public List<String> dlqTopics() {
        return Topics.ALL.stream().map(Topics::dlqFor).toList();
    }

    /**
     * Lists up to {@code limit} messages currently in {@code dlqTopic}, oldest first.
     * Read-only: uses a throwaway group and never commits, so repeated calls see the same
     * records until they're replayed or expire.
     */
    public List<DlqMessage> list(String dlqTopic, int limit) {
        List<DlqMessage> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitionsOf(consumer, dlqTopic);
            if (partitions.isEmpty()) {
                return out;
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.currentTimeMillis() + 10_000;
            while (out.size() < limit && System.currentTimeMillis() < deadline) {
                var records = consumer.poll(POLL);
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, byte[]> r : records) {
                    out.add(toMessage(r));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Total messages across all partitions of {@code dlqTopic} (end-offset sum). */
    public long count(String dlqTopic) {
        try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitionsOf(consumer, dlqTopic);
            if (partitions.isEmpty()) {
                return 0L;
            }
            consumer.assign(partitions);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long total = 0L;
            for (TopicPartition tp : partitions) {
                total += end.getOrDefault(tp, 0L) - begin.getOrDefault(tp, 0L);
            }
            return total;
        }
    }

    /**
     * Replays the record at {@code (dlqTopic, partition, offset)} back to its original
     * topic, then commits the DLQ offset past it so it isn't replayed again. Returns the
     * original topic it was re-published to.
     *
     * @throws IllegalStateException if no record exists at that offset, or it carries no
     *                               {@code x-original-topic} header to replay to.
     */
    public String replay(String dlqTopic, int partition, long offset) {
        TopicPartition tp = new TopicPartition(dlqTopic, partition);
        try (KafkaConsumer<String, byte[]> consumer = newConsumer();
             KafkaProducer<String, byte[]> producer = newProducer()) {
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);

            ConsumerRecord<String, byte[]> target = pollAt(consumer, tp, offset);
            if (target == null) {
                throw new IllegalStateException(
                    "No DLQ record at " + dlqTopic + "-" + partition + "@" + offset);
            }
            String originalTopic = header(target, DlqPublisher.HEADER_ORIGINAL_TOPIC);
            if (originalTopic == null) {
                throw new IllegalStateException(
                    "DLQ record has no " + DlqPublisher.HEADER_ORIGINAL_TOPIC + " header; cannot replay");
            }

            producer.send(new ProducerRecord<>(originalTopic, target.key(), target.value()));
            producer.flush();

            // Commit past the replayed record so a future admin call doesn't re-list it as
            // pending. (Uses the shared admin group; harmless for other reads which
            // seek-to-beginning explicitly.)
            consumer.commitSync(Map.of(tp,
                new org.apache.kafka.clients.consumer.OffsetAndMetadata(offset + 1)));

            log.info("Replayed DLQ record {}-{}@{} (key={}) to original topic {}",
                dlqTopic, partition, offset, target.key(), originalTopic);
            return originalTopic;
        }
    }

    private ConsumerRecord<String, byte[]> pollAt(
        KafkaConsumer<String, byte[]> consumer, TopicPartition tp, long offset
    ) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, byte[]> r : consumer.poll(POLL)) {
                if (r.partition() == tp.partition() && r.offset() == offset) {
                    return r;
                }
            }
        }
        return null;
    }

    private List<TopicPartition> partitionsOf(KafkaConsumer<String, byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return List.of();
        }
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(topic, info.partition()));
        }
        return partitions;
    }

    private DlqMessage toMessage(ConsumerRecord<String, byte[]> r) {
        return new DlqMessage(
            r.topic(),
            r.partition(),
            r.offset(),
            r.key(),
            header(r, DlqPublisher.HEADER_ORIGINAL_TOPIC),
            header(r, DlqPublisher.HEADER_FAILURE_REASON),
            header(r, DlqPublisher.HEADER_ATTEMPTS)
        );
    }

    private static String header(ConsumerRecord<String, byte[]> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private KafkaConsumer<String, byte[]> newConsumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, ADMIN_GROUP);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(p);
    }

    private KafkaProducer<String, byte[]> newProducer() {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(p);
    }
}
