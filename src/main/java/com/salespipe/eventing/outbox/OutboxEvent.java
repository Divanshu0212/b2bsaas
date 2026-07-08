package com.salespipe.eventing.outbox;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Maps {@code outbox_events} (see docs/plan/00-overview.md §4). One row per business
 * event, written in the same transaction as the domain write it describes.
 *
 * <p>No {@code published} flag here — this is the CDC-only shape (Debezium reads the
 * WAL); the polling-relay fallback variant (T2.3) is a separate concern.
 *
 * <p>Shape intentionally mirrors {@link com.salespipe.eventing.EventEnvelope}
 * (orgId/aggregateType/aggregateId/eventType/traceId) so a CDC-relayed row maps onto
 * the canonical envelope without renaming fields.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends TenantEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, UUID orgId, String aggregateType, String aggregateId,
                        String eventType, String payload, String traceId) {
        this.id = id;
        this.orgId = orgId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.traceId = traceId;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getTraceId() { return traceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
