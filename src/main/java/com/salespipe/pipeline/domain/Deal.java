package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deals")
public class Deal extends TenantEntity {
    @Id private UUID id;
    @Column(name = "lead_id") private UUID leadId;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "stage_id") private UUID stageId;
    @Column(name = "owner_id") private UUID ownerId;
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 3) private String currency;
    @Column(name = "expected_close_date") private LocalDate expectedCloseDate;
    @Column(name = "entered_stage_at") private OffsetDateTime enteredStageAt;
    @Version private int version;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;

    protected Deal() {}
    public Deal(UUID id, UUID orgId, UUID stageId) {
        this.id = id; this.orgId = orgId; this.stageId = stageId;
        this.enteredStageAt = OffsetDateTime.now();
        this.createdAt = this.enteredStageAt; this.updatedAt = this.enteredStageAt;
    }
    @PreUpdate void touch() { this.updatedAt = OffsetDateTime.now(); }
    public UUID getId() { return id; }
    public UUID getStageId() { return stageId; }
    public void moveToStage(UUID stageId) {
        this.stageId = stageId; this.enteredStageAt = OffsetDateTime.now();
    }
    public UUID getLeadId() { return leadId; }
    public void setLeadId(UUID v) { this.leadId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDate getExpectedCloseDate() { return expectedCloseDate; }
    public void setExpectedCloseDate(LocalDate v) { this.expectedCloseDate = v; }
    public int getVersion() { return version; }
}
