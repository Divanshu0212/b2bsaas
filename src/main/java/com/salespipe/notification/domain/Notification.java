package com.salespipe.notification.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Maps {@code notifications} ({@code V6__notifications.sql}, T2.7,
 * docs/plan/00-overview.md §4/§9). Minimal in-app notification row — full delivery
 * (email/push), read-state UX, and preferences are explicitly Phase 4+ scope per the
 * plan ("full notifications Phase 4-ish; minimal in-app `notifications` row now").
 *
 * <p>Follows the same simple-{@code @Id}, non-{@code Persistable} shape as {@link
 * com.salespipe.crmcore.domain.Lead}/{@link com.salespipe.pipeline.domain.Deal} (a
 * fresh manually-assigned UUID is always a genuine {@code INSERT} the first — and only
 * — time it is saved) rather than {@code Activity}/{@code ProcessedEvent}'s {@link
 * org.springframework.data.domain.Persistable} pattern, since this table is not
 * partitioned and has no composite key.
 */
@Entity
@Table(name = "notifications")
public class Notification extends TenantEntity {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String type;

    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected Notification() {}

    public Notification(UUID id, UUID orgId, UUID userId, String type, String payload) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
        this.type = type;
        this.payload = payload;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public OffsetDateTime getReadAt() { return readAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
