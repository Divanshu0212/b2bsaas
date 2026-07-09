package com.salespipe.eventing.producer;

import com.salespipe.eventing.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Thin helper around {@link KafkaTemplate} for publishing a canonical {@link EventEnvelope}.
 * Keys every send on {@code aggregate_id} for per-aggregate ordering (overview §5).
 *
 * <p>Scaffolding note: this is a direct producer, used by the smoke test in T2.1. From T2.2
 * onward, the actual write path is the transactional outbox (write to {@code outbox_events}
 * in the same transaction as the business change; Debezium relays to Kafka) — domain code
 * should not call this directly once {@code OutboxRecorder} exists.
 */
@Component
public class EventPublisher {

    /**
     * W3C Trace Context header name (T4.2). Carries the traceparent captured at
     * outbox-write time across the async Kafka boundary so a consumer can rehydrate the
     * span context and the whole request→outbox→relay→consumer flow shows as one trace.
     */
    public static final String TRACEPARENT_HEADER = "traceparent";

    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, EventEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, EventEnvelope>> publish(String topic, EventEnvelope event) {
        return publish(topic, event, null);
    }

    /**
     * Publishes with an explicit {@code traceparent} header (T4.2). When {@code traceparent}
     * is null/blank no header is added — an event recorded before any active span (e.g. a
     * background job) simply carries no trace, rather than a bogus one.
     */
    public CompletableFuture<SendResult<String, EventEnvelope>> publish(
        String topic, EventEnvelope event, String traceparent
    ) {
        ProducerRecord<String, EventEnvelope> record =
            new ProducerRecord<>(topic, event.aggregateId(), event);
        if (traceparent != null && !traceparent.isBlank()) {
            record.headers().add(TRACEPARENT_HEADER, traceparent.getBytes(StandardCharsets.UTF_8));
        }
        return kafkaTemplate.send(record);
    }
}
