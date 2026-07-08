package com.salespipe.eventing.producer;

import com.salespipe.eventing.EventEnvelope;
import java.util.concurrent.CompletableFuture;
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

    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, EventEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, EventEnvelope>> publish(String topic, EventEnvelope event) {
        return kafkaTemplate.send(topic, event.aggregateId(), event);
    }
}
