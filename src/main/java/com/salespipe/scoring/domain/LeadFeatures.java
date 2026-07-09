package com.salespipe.scoring.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps {@code lead_features} ({@code V7__lead_features.sql}, T3.1). One row per lead,
 * maintained asynchronously by {@link
 * com.salespipe.scoring.consumer.FeatureAggregationConsumer} from the event stream —
 * the feature store the phase-3 plan folds into idempotent-consumer discipline
 * (overview change #3) so it is consistency-managed, not a silent dual-write.
 *
 * <p>Same simple-{@code @Id} shape as {@link com.salespipe.crmcore.domain.Lead}. The
 * counters ({@code emailOpenCount}, {@code emailClickCount}, {@code activityCount30d})
 * are incremented in place by the aggregation consumer inside its dedupe transaction, so
 * a redelivered event never double-counts (the {@code processed_events} row blocks the
 * second handler run — see {@link com.salespipe.eventing.consumer.IdempotentConsumer}).
 */
@Entity
@Table(name = "lead_features")
public class LeadFeatures extends TenantEntity {

    @Id
    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "email_open_count")
    private int emailOpenCount;

    @Column(name = "email_click_count")
    private int emailClickCount;

    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;

    @Column(name = "activity_count_30d")
    private int activityCount30d;

    @Column(name = "deal_velocity_days")
    private java.math.BigDecimal dealVelocityDays;

    @Column(name = "company_size_bucket")
    private String companySizeBucket;

    @Column(name = "feature_version")
    private int featureVersion;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected LeadFeatures() {}

    /** New empty feature row for a lead, feature_version = 1. */
    public LeadFeatures(UUID leadId, UUID orgId) {
        this.leadId = leadId;
        this.orgId = orgId;
        this.featureVersion = 1;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Bump {@code updated_at} — call after any counter/field mutation. */
    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getLeadId() { return leadId; }
    public int getEmailOpenCount() { return emailOpenCount; }
    public void incrementEmailOpenCount() { this.emailOpenCount++; }
    public int getEmailClickCount() { return emailClickCount; }
    public void incrementEmailClickCount() { this.emailClickCount++; }
    public OffsetDateTime getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(OffsetDateTime v) { this.lastActivityAt = v; }
    public int getActivityCount30d() { return activityCount30d; }
    public void incrementActivityCount30d() { this.activityCount30d++; }
    public java.math.BigDecimal getDealVelocityDays() { return dealVelocityDays; }
    public void setDealVelocityDays(java.math.BigDecimal v) { this.dealVelocityDays = v; }
    public String getCompanySizeBucket() { return companySizeBucket; }
    public void setCompanySizeBucket(String v) { this.companySizeBucket = v; }
    public int getFeatureVersion() { return featureVersion; }
}
