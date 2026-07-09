package com.salespipe.eventing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Small helper so domain modules don't hand-roll the outbox write (overview §4/§5,
 * plan T2.2). Call {@link #record} from inside the caller's existing
 * {@code @Transactional} business method — this class deliberately does NOT open its
 * own transaction (no {@code @Transactional(REQUIRES_NEW)} or similar). The whole
 * point of the outbox pattern is that the {@code outbox_events} insert commits or
 * rolls back atomically with the business write it describes; wrapping it in its own
 * transaction would defeat that.
 *
 * <p>{@code trace_id} (T4.2) is the current span's W3C traceparent
 * ({@code 00-<traceId>-<spanId>-<flags>}), captured here at outbox-write time so the
 * async relay can re-emit it as a Kafka header and the consumer can rehydrate the span
 * context — making request→outbox→relay→consumer one connected trace across the async
 * boundary. Falls back to the {@code trace_id} MDC value, then {@code null}, when no
 * OTel span is active (e.g. a plain background thread) rather than inventing a trace id.
 */
@Service
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final TenantContext tenant;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository repository, TenantContext tenant, ObjectMapper objectMapper) {
        this.repository = repository;
        this.tenant = tenant;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds and saves an outbox row shaped like {@link com.salespipe.eventing.EventEnvelope}
     * (aggregateType/aggregateId/eventType/orgId/traceId), joining whatever transaction
     * is already active on the calling thread.
     */
    public OutboxEvent record(String aggregateType, String aggregateId, String eventType, JsonNode payload) {
        return record(tenant.getOrgId(), aggregateType, aggregateId, eventType, payload);
    }

    /**
     * Same as {@link #record(String, String, String, JsonNode)} but with an explicit
     * {@code orgId}, for callers that run <b>off the request thread</b> where the
     * {@code @RequestScope} {@link TenantContext} is not populated — notably async score
     * recompute driven from a Kafka consumer thread (T3.4's {@code ScoringService}). The
     * org is already known from the inbound event, so pass it directly rather than
     * relying on request scope.
     */
    public OutboxEvent record(UUID orgId, String aggregateType, String aggregateId, String eventType, JsonNode payload) {
        String payloadJson = writePayload(payload);
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(),
            orgId,
            aggregateType,
            aggregateId,
            eventType,
            payloadJson,
            currentTraceparent()
        );
        return repository.save(event);
    }

    /**
     * The active span's W3C traceparent, or the {@code trace_id} MDC fallback, or null.
     * Built by hand from the {@link SpanContext} (rather than pulling in the OTel
     * propagator API) since the format is fixed: {@code 00-<32 hex traceId>-<16 hex
     * spanId>-<2 hex flags>}.
     */
    private String currentTraceparent() {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            String flags = ctx.getTraceFlags() != null ? ctx.getTraceFlags().asHex() : TraceFlags.getDefault().asHex();
            return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + flags;
        }
        return MDC.get("trace_id");
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox payload is not serializable", e);
        }
    }
}
