package com.salespipe.common.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager em;

    private final TenantContext tenantContext;

    public TenantFilterAspect(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /** Enable the Hibernate tenant filter for the current session. */
    public void enable() {
        if (RequestContextHolder.getRequestAttributes() == null) return;
        if (!tenantContext.isSet()) return;
        em.unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("orgId", tenantContext.getOrgId());
    }
}
