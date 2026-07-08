package com.salespipe.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id private UUID id;
    private String name;
    private String plan;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected Organization() {}
    public Organization(UUID id, String name) {
        this.id = id; this.name = name; this.plan = "FREE";
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
}
