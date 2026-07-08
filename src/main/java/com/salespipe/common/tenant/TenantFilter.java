package com.salespipe.common.tenant;

import jakarta.servlet.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

/**
 * Reads the {@link AuthPrincipal} left in the SecurityContext by {@code JwtAuthFilter}
 * and enables the Hibernate tenant filter for the request.
 *
 * <p>Intentionally NOT a {@code @Component}: see the note on {@code JwtAuthFilter}.
 * This filter is exposed as a plain {@code @Bean} in {@code SecurityConfig} and wired
 * into the security chain with {@code addFilterAfter(tenantFilter, JwtAuthFilter.class)}
 * so it always runs after the SecurityContext has been populated.
 */
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
        boolean openedEntityManager = false;
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) {
            tenantContext.setOrgId(p.orgId());
            openedEntityManager = filterAspect.enable();
        }
        try {
            chain.doFilter(req, res);
        } finally {
            filterAspect.close(openedEntityManager);
        }
    }

    /** Principal carried by the Authentication set in JwtAuthFilter. */
    public record AuthPrincipal(UUID userId, UUID orgId) {}
}
