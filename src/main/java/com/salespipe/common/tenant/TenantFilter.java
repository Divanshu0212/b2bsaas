package com.salespipe.common.tenant;

import jakarta.servlet.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(20)
public class TenantFilter implements Filter {

    private final TenantContext tenantContext;
    private final TenantFilterAspect filterAspect;

    public TenantFilter(TenantContext tenantContext, TenantFilterAspect filterAspect) {
        this.tenantContext = tenantContext;
        this.filterAspect = filterAspect;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) {
            tenantContext.setOrgId(p.orgId());
            filterAspect.enable();
        }
        chain.doFilter(req, res);
    }

    /** Principal carried by the Authentication set in JwtAuthFilter. */
    public record AuthPrincipal(UUID userId, UUID orgId) {}
}
