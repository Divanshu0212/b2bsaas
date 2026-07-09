package com.salespipe.common.ratelimit;

import com.salespipe.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the per-tenant API quota (T4.6). Runs after {@code TenantFilter} in the
 * security chain, so {@link TenantContext} is populated for authenticated requests; on an
 * exhausted budget it returns HTTP 429 with a {@code Retry-After} header before the
 * request reaches any controller.
 *
 * <p>Only authenticated, org-scoped API calls are limited here. Requests with no resolved
 * tenant (unauthenticated public endpoints — {@code /auth}, {@code /emails} tracking,
 * {@code /webhooks}, actuator) pass through untouched: {@code /emails} already has its own
 * per-tenant limiter, and the rest are intentionally public.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final ApiRateLimiter rateLimiter;
    private final TenantContext tenantContext;
    private final ApiRateLimitProperties properties;

    public ApiRateLimitFilter(
        ApiRateLimiter rateLimiter, TenantContext tenantContext, ApiRateLimitProperties properties
    ) {
        this.rateLimiter = rateLimiter;
        this.tenantContext = tenantContext;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        // No tenant resolved => not an authenticated org-scoped call; leave it alone.
        if (properties.isEnabled() && tenantContext.isSet()
            && !rateLimiter.tryConsume(tenantContext.getOrgId())) {
            response.setStatus(429); // Too Many Requests (not a constant in the servlet API)
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
