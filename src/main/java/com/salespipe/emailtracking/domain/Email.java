package com.salespipe.emailtracking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbound tracked email metadata (overview §4). Deliberately does NOT extend
 * {@link com.salespipe.common.tenant.TenantEntity} / participate in the Hibernate
 * {@code tenantFilter}: the request paths that read this table at serve time
 * ({@code TrackingController}, {@code WebhookController}) are unauthenticated public
 * HTTP endpoints (mail clients loading a pixel, ESP webhook callers) with no JWT, so
 * there is no populated {@link com.salespipe.common.tenant.TenantContext} to enable the
 * filter against in the first place. Org scoping for those flows is instead an explicit
 * {@code org_id} column read off the row found by {@code tracking_id}, never a
 * request-scoped filter. See {@code TrackingSigner} for how {@code org_id} still gets
 * baked into the anti-forgery signature despite that.
 */
@Entity
@Table(name = "emails")
public class Email {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "deal_id")
    private UUID dealId;

    @Column(name = "lead_id")
    private UUID leadId;

    private String subject;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "tracking_id", nullable = false)
    private UUID trackingId;

    @Column(name = "tracking_sig", nullable = false)
    private String trackingSig;

    protected Email() {}

    public Email(UUID id, UUID orgId, UUID dealId, UUID leadId, String subject,
            UUID trackingId, String trackingSig) {
        this.id = id;
        this.orgId = orgId;
        this.dealId = dealId;
        this.leadId = leadId;
        this.subject = subject;
        this.sentAt = OffsetDateTime.now();
        this.trackingId = trackingId;
        this.trackingSig = trackingSig;
    }

    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public UUID getDealId() { return dealId; }
    public UUID getLeadId() { return leadId; }
    public String getSubject() { return subject; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public UUID getTrackingId() { return trackingId; }
    public String getTrackingSig() { return trackingSig; }
}
