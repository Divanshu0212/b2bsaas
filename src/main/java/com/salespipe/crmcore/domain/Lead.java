package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "leads")
public class Lead extends TenantEntity {
    @Id private UUID id;
    @Column(name = "contact_id") private UUID contactId;
    @Column(name = "account_id") private UUID accountId;
    private String source;
    @Enumerated(EnumType.STRING) private LeadStatus status;
    @Column(name = "raw_notes") private String rawNotes;
    @Column(name = "owner_id") private UUID ownerId;
    @Version private int version;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;

    protected Lead() {}
    public Lead(UUID id, UUID orgId, LeadStatus status) {
        this.id = id; this.orgId = orgId; this.status = status;
        this.createdAt = OffsetDateTime.now(); this.updatedAt = this.createdAt;
    }
    @PreUpdate void touch() { this.updatedAt = OffsetDateTime.now(); }
    public UUID getId() { return id; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID v) { this.contactId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public LeadStatus getStatus() { return status; }
    public void setStatus(LeadStatus v) { this.status = v; }
    public String getRawNotes() { return rawNotes; }
    public void setRawNotes(String v) { this.rawNotes = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public int getVersion() { return version; }
}
