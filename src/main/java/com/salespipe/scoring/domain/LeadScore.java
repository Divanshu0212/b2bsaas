package com.salespipe.scoring.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Maps {@code lead_scores} ({@code V8__lead_scores.sql}, T3.4/T3.5). Append-only score
 * history: one row per async recompute, carrying the MLflow {@code model_version} that
 * produced it and the SHAP {@code top_factors} JSON. {@code GET /leads/{id}/score}
 * returns the latest of these plus the tail as history.
 */
@Entity
@Table(name = "lead_scores")
public class LeadScore extends TenantEntity {

    @Id
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(nullable = false)
    private BigDecimal score;

    @Column(name = "model_version")
    private String modelVersion;

    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "top_factors", columnDefinition = "jsonb")
    private String topFactors;

    @Column(name = "scored_at")
    private OffsetDateTime scoredAt;

    protected LeadScore() {}

    public LeadScore(UUID id, UUID orgId, UUID leadId, BigDecimal score, String modelVersion, String topFactors) {
        this.id = id;
        this.orgId = orgId;
        this.leadId = leadId;
        this.score = score;
        this.modelVersion = modelVersion;
        this.topFactors = topFactors;
        this.scoredAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public BigDecimal getScore() { return score; }
    public String getModelVersion() { return modelVersion; }
    public String getTopFactors() { return topFactors; }
    public OffsetDateTime getScoredAt() { return scoredAt; }
}
