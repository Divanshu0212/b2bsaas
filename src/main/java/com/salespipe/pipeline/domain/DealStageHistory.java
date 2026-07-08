package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deal_stage_history")
public class DealStageHistory extends TenantEntity {
    @Id private UUID id;
    @Column(name = "deal_id") private UUID dealId;
    @Column(name = "from_stage_id") private UUID fromStageId;
    @Column(name = "to_stage_id") private UUID toStageId;
    @Column(name = "changed_by") private UUID changedBy;
    @Column(name = "changed_at") private OffsetDateTime changedAt;

    protected DealStageHistory() {}
    public DealStageHistory(UUID id, UUID orgId, UUID dealId, UUID fromStageId,
                            UUID toStageId, UUID changedBy) {
        this.id = id; this.orgId = orgId; this.dealId = dealId;
        this.fromStageId = fromStageId; this.toStageId = toStageId;
        this.changedBy = changedBy; this.changedAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
}
