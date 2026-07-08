package com.salespipe.activity.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.domain.Persistable;

/**
 * Maps {@code activities} ({@code V4__activities.sql}, T2.5, docs/plan/00-overview.md
 * §4). Append-only polymorphic timeline row — {@code entity_type}/{@code entity_id}
 * identify the lead/deal/contact this activity belongs to.
 *
 * <p><b>Never updated.</b> Every consumer in {@code activity/consumer/} only ever
 * inserts a new row; there is no update path. Implements {@link Persistable} and always
 * reports {@link #isNew()} {@code true} for the same reason as {@code ProcessedEvent}
 * (see its javadoc): a manually-assigned (non-{@code @GeneratedValue}) {@code
 * @EmbeddedId} would otherwise make Spring Data JPA {@code SELECT}-then-{@code MERGE}
 * instead of {@code INSERT}, which is both wasteful (an extra round trip on every write)
 * and semantically wrong for a table that is insert-only by design.
 *
 * <p>{@code activity_type} is a free-text column, not a JPA enum — see {@link
 * ActivityType}'s javadoc for why.
 */
@Entity
@Table(name = "activities")
public class Activity extends TenantEntity implements Persistable<ActivityId> {

    @EmbeddedId
    private ActivityId id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "activity_type", nullable = false)
    private String activityType;

    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Activity() {}

    public Activity(UUID id, UUID orgId, String entityType, UUID entityId,
                     String activityType, String payload, UUID createdBy) {
        this.id = new ActivityId(id, OffsetDateTime.now());
        this.orgId = orgId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.activityType = activityType;
        this.payload = payload;
        this.createdBy = createdBy;
    }

    @Override
    public ActivityId getId() { return id; }

    public UUID getEntityId() { return entityId; }
    public String getEntityType() { return entityType; }
    public String getActivityType() { return activityType; }
    public String getPayload() { return payload; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return id.getCreatedAt(); }

    @Transient
    @Override
    public boolean isNew() {
        // Always "new" — activities is append-only, never updated (see class javadoc).
        // Forces Spring Data JPA to persist() (INSERT), not merge() (SELECT-then-
        // UPDATE/INSERT), matching the actual write pattern of every consumer here.
        return true;
    }
}
