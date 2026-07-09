package com.salespipe.eventing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Normalized shape a consumer handler actually works with, regardless of which relay
 * path delivered the Kafka record. See {@code debezium/README.md} ("Value shape gotcha
 * for future consumer work (T2.4+)") for the asymmetry this type exists to erase:
 *
 * <ul>
 *   <li><b>CDC path</b> (Debezium outbox-router SMT, default/production relay): the
 *       Kafka message <i>value</i> is the outbox row's raw {@code payload} JSONB column
 *       only — no top-level {@code event_id}/{@code event_type}/etc. Envelope fields
 *       instead arrive as: key = {@code aggregate_id}; header {@code id} = the outbox
 *       row's id (Debezium's outbox EventRouter SMT unconditionally adds this header —
 *       see {@code table.field.event.id}, independent of {@code
 *       table.fields.additional.placement}); headers {@code aggregateType}/{@code
 *       orgId}/{@code traceId} = via {@code table.fields.additional.placement}
 *       (configured in {@code debezium/outbox-connector.json}); {@code event_type} is
 *       implicit in the topic name (routing is by {@code event_type}, identity
 *       template).</li>
 *   <li><b>Direct-produce / polling-relay path</b> ({@link
 *       com.salespipe.eventing.producer.EventPublisher}, {@link
 *       com.salespipe.eventing.outbox.PollingRelay}): the Kafka message value IS a full
 *       {@link com.salespipe.eventing.EventEnvelope} JSON document (all fields
 *       top-level in the value; no reliance on headers).</li>
 * </ul>
 *
 * <p>{@link InboundEventNormalizer} builds this from a raw {@code ConsumerRecord<String,
 * String>} once, before any dedupe/handler logic runs, so {@link IdempotentConsumer}
 * subclasses never need to know which relay path a message came from.
 */
public record InboundEvent(
    UUID eventId,
    String eventType,
    String orgId,
    String aggregateType,
    String aggregateId,
    String traceId,
    JsonNode payload,
    String topic,
    String key
) {
}
