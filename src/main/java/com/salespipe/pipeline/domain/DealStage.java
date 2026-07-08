package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "deal_stages")
public class DealStage extends TenantEntity {
    @Id private UUID id;
    private String name;
    private int position;
    @Column(name = "is_won") private boolean isWon;
    @Column(name = "is_lost") private boolean isLost;

    protected DealStage() {}
    public DealStage(UUID id, UUID orgId, String name, int position,
                     boolean isWon, boolean isLost) {
        this.id = id; this.orgId = orgId; this.name = name;
        this.position = position; this.isWon = isWon; this.isLost = isLost;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getPosition() { return position; }
    public boolean isWon() { return isWon; }
    public boolean isLost() { return isLost; }
}
