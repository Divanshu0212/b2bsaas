package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account extends TenantEntity {
    @Id private UUID id;
    private String name;
    private String industry;
    @Column(name = "employee_count") private Integer employeeCount;
    private String website;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected Account() {}
    public Account(UUID id, UUID orgId, String name) {
        this.id = id; this.orgId = orgId; this.name = name;
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getIndustry() { return industry; }
    public void setIndustry(String i) { this.industry = i; }
    public Integer getEmployeeCount() { return employeeCount; }
    public void setEmployeeCount(Integer c) { this.employeeCount = c; }
    public String getWebsite() { return website; }
    public void setWebsite(String w) { this.website = w; }
}
