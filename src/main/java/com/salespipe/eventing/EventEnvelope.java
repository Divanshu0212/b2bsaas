package com.salespipe.eventing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Canonical event envelope for every topic (see docs/plan/00-overview.md §5).
 *
 * <p>Producers populate this shape and hand it to {@link com.salespipe.eventing.producer.EventPublisher};
 * it is serialized via the Confluent JSON Schema serializer and validated against the
 * schema registered for {@code <topic>-value} in {@code eventing/schema}.
 */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String orgId,
    String aggregateType,
    String aggregateId,
    Instant occurredAt,
    String traceId,
    JsonNode payload
) {

    /**
     * Convenience factory for producer call sites: generates {@code eventId} and
     * {@code occurredAt}, leaving the caller to supply the business fields.
     */
    public static EventEnvelope of(
        String eventType,
        int schemaVersion,
        String orgId,
        String aggregateType,
        String aggregateId,
        String traceId,
        JsonNode payload
    ) {
        return new EventEnvelope(
            UUID.randomUUID(),
            eventType,
            schemaVersion,
            orgId,
            aggregateType,
            aggregateId,
            Instant.now(),
            traceId,
            payload
        );
    }
}
