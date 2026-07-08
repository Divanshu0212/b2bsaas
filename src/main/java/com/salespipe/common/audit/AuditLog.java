package com.salespipe.common.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    @Column(name = "actor_id") private UUID actorId;
    private String action;
    @Column(name = "entity_type") private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb") private String diff;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected AuditLog() {}
    public AuditLog(UUID id, UUID orgId, UUID actorId, String action,
                    String entityType, UUID entityId, String diff) {
        this.id = id; this.orgId = orgId; this.actorId = actorId;
        this.action = action; this.entityType = entityType;
        this.entityId = entityId; this.diff = diff;
        this.createdAt = OffsetDateTime.now();
    }
}
