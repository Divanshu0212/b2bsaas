package com.salespipe.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "family_id") private UUID familyId;
    @Column(name = "token_hash") private String tokenHash;
    @Column(name = "parent_id") private UUID parentId;
    private boolean used;
    @Column(name = "expires_at") private OffsetDateTime expiresAt;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected RefreshToken() {}
    public RefreshToken(UUID id, UUID orgId, UUID userId, UUID familyId,
                        String tokenHash, UUID parentId, OffsetDateTime expiresAt) {
        this.id = id; this.orgId = orgId; this.userId = userId;
        this.familyId = familyId; this.tokenHash = tokenHash;
        this.parentId = parentId; this.expiresAt = expiresAt;
        this.used = false; this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public boolean isUsed() { return used; }
    public void markUsed() { this.used = true; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
