package com.salespipe.common.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

@MappedSuperclass
@FilterDef(name = "tenantFilter",
        parameters = @ParamDef(name = "orgId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "org_id = :orgId")
public abstract class TenantEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    protected UUID orgId;

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
}
