package com.salespipe.identity.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends TenantEntity {
    @Id private UUID id;
    @Column(columnDefinition = "citext") private String email;
    @Column(name = "password_hash") private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected User() {}
    public User(UUID id, UUID orgId, String email, String passwordHash, Role role) {
        this.id = id; this.orgId = orgId; this.email = email;
        this.passwordHash = passwordHash; this.role = role;
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
}
