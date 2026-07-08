package com.salespipe.eventing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Builds a normalized {@link InboundEvent} from a raw Kafka record, erasing the
 * CDC-vs-direct-produce value-shape asymmetry documented on {@link InboundEvent} and in
 * {@code debezium/README.md}.
 *
 * <p>Consumers are wired with a plain {@code byte[]} value deserializer (see {@code
 * EventConsumerConfig}) rather than the {@code EventEnvelope}-typed {@code
 * KafkaJsonSchemaDeserializer}, for two independent reasons (see {@code
 * EventConsumerConfig}'s class javadoc for the full explanation):
 * <ol>
 *   <li>the CDC path's value often is NOT an {@code EventEnvelope} at all (it's the
 *       bare {@code payload} JSONB) — a schema-registry-bound envelope deserializer
 *       would throw on every CDC-relayed message;</li>
 *   <li>even the direct-produce/polling-relay path's value is not plain JSON — it's
 *       Confluent-wire-format-framed (1 magic byte + 4-byte schema id prefix) by
 *       {@link com.salespipe.eventing.producer.ProducerConfig}'s {@code
 *       KafkaJsonSchemaSerializer}.</li>
 * </ol>
 * This class strips the framing when present and parses the raw JSON itself,
 * branching into the "normalize both shapes into a common internal type before
 * dedupe/handler logic runs" approach called out in the T2.4 task brief.
 */
@Component
public class InboundEventNormalizer {

    /** Header Debezium's outbox EventRouter SMT unconditionally sets to the outbox row id. */
    static final String HEADER_ID = "id";
    static final String HEADER_AGGREGATE_TYPE = "aggregateType";
    static final String HEADER_ORG_ID = "orgId";
    static final String HEADER_TRACE_ID = "traceId";

    /**
     * Confluent wire-format framing: 1 magic byte ({@code 0x0}) + 4-byte big-endian
     * schema id, before the actual (Avro/Protobuf/JSON) payload bytes. Applies to
     * anything serialized by a Confluent {@code KafkaJsonSchemaSerializer} (the
     * direct-produce {@link com.salespipe.eventing.producer.EventPublisher} and the
     * polling-relay path both use it via {@link
     * com.salespipe.eventing.producer.ProducerConfig}). The CDC path (Debezium's plain
     * {@code JsonConverter}, {@code schemas.enable=false}) does NOT use this framing —
     * its value bytes are plain JSON from byte 0.
     */
    private static final int CONFLUENT_FRAME_PREFIX_LENGTH = 5;
    private static final byte CONFLUENT_MAGIC_BYTE = 0x0;

    private final ObjectMapper objectMapper;

    public InboundEventNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InboundEvent normalize(ConsumerRecord<String, byte[]> record) throws Exception {
        String idHeader = headerValue(record, HEADER_ID);

        if (idHeader != null) {
            return fromCdcShape(record, idHeader);
        }
        return fromEnvelopeShape(record);
    }

    /**
     * CDC path: the {@code id} header (present) is the outbox row's id / event_id.
     * Value is the raw {@code payload} JSONB only (unframed plain JSON — Debezium's
     * outbox-router SMT does not go through the Confluent JSON Schema serializer).
     * Other envelope fields come from headers (per {@code
     * debezium/outbox-connector.json}'s {@code table.fields.additional.placement}) and
     * the Kafka key ({@code aggregate_id}). {@code event_type} is implicit in the topic
     * name (routing convention documented in {@code debezium/README.md}).
     */
    private InboundEvent fromCdcShape(ConsumerRecord<String, byte[]> record, String idHeader) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        return new InboundEvent(
            UUID.fromString(idHeader),
            record.topic(),
            headerValue(record, HEADER_ORG_ID),
            headerValue(record, HEADER_AGGREGATE_TYPE),
            record.key(),
            headerValue(record, HEADER_TRACE_ID),
            payload,
            record.topic(),
            record.key()
        );
    }

    /**
     * Direct-produce ({@link com.salespipe.eventing.producer.EventPublisher}) /
     * polling-relay ({@link com.salespipe.eventing.outbox.PollingRelay}) path: value is
     * a full {@link com.salespipe.eventing.EventEnvelope} JSON document, Confluent-wire-
     * format-framed — strip the 5-byte magic-byte+schema-id prefix before parsing.
     */
    private InboundEvent fromEnvelopeShape(ConsumerRecord<String, byte[]> record) throws Exception {
        JsonNode envelope = objectMapper.readTree(stripConfluentFraming(record.value()));
        return new InboundEvent(
            UUID.fromString(envelope.get("eventId").asText()),
            envelope.get("eventType").asText(),
            envelope.path("orgId").asText(null),
            envelope.path("aggregateType").asText(null),
            envelope.path("aggregateId").asText(null),
            envelope.path("traceId").asText(null),
            envelope.get("payload"),
            record.topic(),
            record.key()
        );
    }

    /**
     * Strips the Confluent wire-format prefix (1 magic byte + 4-byte schema id) if
     * present. Detected by the leading magic byte rather than assumed unconditionally,
     * so this stays correct even if a future producer writes unframed JSON directly to
     * one of these topics.
     */
    private static byte[] stripConfluentFraming(byte[] value) {
        if (value.length > CONFLUENT_FRAME_PREFIX_LENGTH && value[0] == CONFLUENT_MAGIC_BYTE) {
            byte[] stripped = new byte[value.length - CONFLUENT_FRAME_PREFIX_LENGTH];
            System.arraycopy(value, CONFLUENT_FRAME_PREFIX_LENGTH, stripped, 0, stripped.length);
            return stripped;
        }
        return value;
    }

    private static String headerValue(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
