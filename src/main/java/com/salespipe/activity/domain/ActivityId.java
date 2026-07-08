package com.salespipe.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@code activities}: {@code (id, created_at)} per
 * docs/plan/00-overview.md §4. Native Postgres RANGE partitioning by {@code created_at}
 * requires the partition key to be part of every unique index, including the primary
 * key — hence the composite key rather than a plain {@code id UUID PRIMARY KEY} (see
 * {@code V4__activities.sql}).
 */
@Embeddable
public class ActivityId implements Serializable {

    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ActivityId() {}

    public ActivityId(UUID id, OffsetDateTime createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActivityId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, createdAt);
    }
}
