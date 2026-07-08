package com.salespipe.eventing.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Maps {@code processed_events} (migrated in {@code V2__outbox.sql}, T2.2 — not
 * re-migrated here). Dedupe record for the idempotent-consumer framework (T2.4,
 * docs/plan/00-overview.md §4): one row per {@code (consumer_group, event_id)} that
 * has been handled. The composite primary key IS the dedupe mechanism — see
 * {@link ProcessedEventRepository} and {@link IdempotentConsumer} for why this is an
 * insert-and-catch-unique-violation, not a check-then-insert.
 *
 * <p>Deliberately has no {@code org_id} — {@code processed_events} is an internal
 * bookkeeping table for delivery semantics, not a tenant-scoped domain read, so it
 * does not extend {@code TenantEntity} / carry the Hibernate tenant filter.
 *
 * <p>Implements {@link Persistable} and always reports {@link #isNew()} {@code true}.
 * Without this, Spring Data JPA's {@code SimpleJpaRepository.save()} — used by {@link
 * IdempotentConsumer} via {@code saveAndFlush} — sees a manually-assigned (non-{@code
 * @GeneratedValue}) {@code @EmbeddedId} and cannot tell "new" from "detached" by id
 * alone, so it does a {@code SELECT} to decide, then either {@code INSERT}s or {@code
 * MERGE}s. On a redelivery, that turns into a silent no-op {@code MERGE} of the
 * existing row instead of a failing {@code INSERT} — the composite-PK unique-violation
 * this whole dedupe pattern depends on never fires, and the "redelivery is deduped"
 * guarantee silently breaks. Forcing {@code isNew() == true} makes Spring Data always
 * call {@code EntityManager.persist()} (a real {@code INSERT}), so the second
 * delivery's insert attempt genuinely collides with the first's committed row.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<ProcessedEventId> {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String consumerGroup, UUID eventId) {
        this.id = new ProcessedEventId(consumerGroup, eventId);
        this.processedAt = OffsetDateTime.now();
    }

    @Override
    public ProcessedEventId getId() { return id; }

    public OffsetDateTime getProcessedAt() { return processedAt; }

    @Transient
    @Override
    public boolean isNew() {
        // Always "new": every ProcessedEvent this class ever constructs represents a
        // fresh dedupe-insert attempt, never an update of an existing row (this table
        // has no update path — see class javadoc). Forces persist()-not-merge(); see
        // class javadoc for why that matters.
        return true;
    }
}
