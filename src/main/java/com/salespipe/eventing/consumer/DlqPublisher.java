package com.salespipe.eventing.consumer;

import com.salespipe.eventing.Topics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes a poison message to {@code <topic>.DLQ} (naming per overview §5, {@link
 * Topics#dlqFor(String)}) after retry exhaustion, carrying the original raw value plus
 * a failure reason. Never silently drops (plan's T2.4 accept criterion).
 *
 * <p>Publishes the original record's raw value unchanged plus headers describing the
 * failure, rather than re-wrapping/re-encoding the payload — the DLQ consumer (a human
 * via a console consumer, or a future replay tool) needs the exact bytes that failed,
 * not a reinterpretation of them.
 */
@Component
public class DlqPublisher {

    /** Header carrying the exception class + message that caused the DLQ routing. */
    public static final String HEADER_FAILURE_REASON = "x-failure-reason";
    /** Header carrying the original source topic (the DLQ topic name itself is derivable, but explicit is safer for tooling). */
    public static final String HEADER_ORIGINAL_TOPIC = "x-original-topic";
    /** Header carrying how many handler attempts were made before giving up. */
    public static final String HEADER_ATTEMPTS = "x-attempts";

    private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);

    private final KafkaTemplate<String, byte[]> dlqKafkaTemplate;

    public DlqPublisher(KafkaTemplate<String, byte[]> dlqKafkaTemplate) {
        this.dlqKafkaTemplate = dlqKafkaTemplate;
    }

    public CompletableFuture<SendResult<String, byte[]>> publish(
        String sourceTopic, String key, byte[] rawValue, int attemptsMade, Throwable failure
    ) {
        String dlqTopic = Topics.dlqFor(sourceTopic);
        String reason = failure.getClass().getName() + ": " + failure.getMessage();

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(dlqTopic, key, rawValue);
        record.headers().add(new RecordHeader(HEADER_FAILURE_REASON, reason.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_ORIGINAL_TOPIC, sourceTopic.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_ATTEMPTS, String.valueOf(attemptsMade).getBytes(StandardCharsets.UTF_8)));

        log.error("Routing poison message to DLQ topic={} key={} attempts={} reason={}",
            dlqTopic, key, attemptsMade, reason);

        return dlqKafkaTemplate.send(record);
    }
}
