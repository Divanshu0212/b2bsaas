package com.salespipe.eventing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
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
 * <p>{@code trace_id} is read from MDC ({@code trace_id}, the key
 * {@code logback-spring.xml} already ships to the log encoder). Real OTel propagation
 * lands in Phase 4 (overview §6.2) — until then this is a placeholder value, per the
 * plan ("populated properly in Phase 4; placeholder MDC value acceptable now"), and
 * falls back to {@code null} when absent rather than inventing a trace id.
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
        String payloadJson = writePayload(payload);
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(),
            tenant.getOrgId(),
            aggregateType,
            aggregateId,
            eventType,
            payloadJson,
            MDC.get("trace_id")
        );
        return repository.save(event);
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox payload is not serializable", e);
        }
    }
}
