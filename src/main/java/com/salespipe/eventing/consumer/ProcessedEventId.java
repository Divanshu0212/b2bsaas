package com.salespipe.eventing.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@code processed_events}: {@code (consumer_group, event_id)}
 * per docs/plan/00-overview.md §4. {@link Embeddable} rather than a generated surrogate
 * key — the whole point of this table is that the composite key IS the uniqueness
 * constraint the dedupe insert relies on (see {@link ProcessedEventRepository}).
 */
@Embeddable
public class ProcessedEventId implements Serializable {

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    protected ProcessedEventId() {}

    public ProcessedEventId(String consumerGroup, UUID eventId) {
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
    }

    public String getConsumerGroup() { return consumerGroup; }
    public UUID getEventId() { return eventId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEventId that)) return false;
        return Objects.equals(consumerGroup, that.consumerGroup) && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumerGroup, eventId);
    }
}
