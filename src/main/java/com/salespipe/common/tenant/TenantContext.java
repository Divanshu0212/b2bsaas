package com.salespipe.common.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

@Component
@RequestScope
public class TenantContext {
    private UUID orgId;

    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getOrgId() { return orgId; }
    public boolean isSet() { return orgId != null; }
}
