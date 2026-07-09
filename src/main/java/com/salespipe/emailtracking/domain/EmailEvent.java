package com.salespipe.emailtracking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only OPENED/CLICKED/BOUNCED record (overview §4). Composite PK
 * {@code (id, occurred_at)} matches the partitioned-table requirement that the
 * partition key be part of every unique constraint (see V5__email_tracking.sql).
 *
 * <p>Not written via {@code EventPublisher}/outbox — per the plan, T2.6's tracking
 * endpoints emit {@code email.event.received} directly and async; this entity is
 * currently unused by the controllers (they only need the Kafka event, not a synchronous
 * DB row) but is included as the JPA mapping for {@code email_events} per the overview §4
 * schema, for future consumers (e.g. an {@code email.event.received} consumer that
 * materializes this table, symmetric with how {@code activities} is populated from
 * Kafka in T2.5) to build on without a further migration.
 */
@Entity
@Table(name = "email_events")
@IdClass(EmailEvent.EmailEventId.class)
public class EmailEvent {

    @Id
    private UUID id;

    @Id
    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "email_id", nullable = false)
    private UUID emailId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EmailEventType eventType;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    protected EmailEvent() {}

    public EmailEvent(UUID id, UUID orgId, UUID emailId, EmailEventType eventType, String ip, String userAgent) {
        this.id = id;
        this.orgId = orgId;
        this.emailId = emailId;
        this.eventType = eventType;
        this.ip = ip;
        this.userAgent = userAgent;
        this.occurredAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public UUID getOrgId() { return orgId; }
    public UUID getEmailId() { return emailId; }
    public EmailEventType getEventType() { return eventType; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }

    /** Composite-PK class required by {@code @IdClass} — must match field names/types on the entity. */
    public static class EmailEventId implements Serializable {
        private UUID id;
        private OffsetDateTime occurredAt;

        public EmailEventId() {}
        public EmailEventId(UUID id, OffsetDateTime occurredAt) {
            this.id = id;
            this.occurredAt = occurredAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EmailEventId that)) return false;
            return Objects.equals(id, that.id) && Objects.equals(occurredAt, that.occurredAt);
        }

        @Override
        public int hashCode() { return Objects.hash(id, occurredAt); }
    }
}
