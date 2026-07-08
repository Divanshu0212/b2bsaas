package com.salespipe.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private final AuditLogRepository repo;
    private final TenantContext tenant;
    private final ObjectMapper json;

    public AuditAspect(AuditLogRepository repo, TenantContext tenant, ObjectMapper json) {
        this.repo = repo; this.tenant = tenant; this.json = json;
    }

    @Pointcut("(@annotation(org.springframework.web.bind.annotation.PostMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.PutMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.PatchMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.DeleteMapping)) " +
              "&& within(com.salespipe..api..*)")
    void mutating() {}

    @AfterReturning(pointcut = "mutating()", returning = "result")
    public void record(JoinPoint jp, Object result) {
        if (!tenant.isSet()) return; // e.g. /auth endpoints run pre-tenant; skip
        try {
            String diff = json.writeValueAsString(result);
            repo.save(new AuditLog(UUID.randomUUID(), tenant.getOrgId(), actor(),
                jp.getSignature().getName(),
                jp.getSignature().getDeclaringType().getSimpleName(), null, diff));
        } catch (Exception ignored) { /* audit must never break the request */ }
    }

    private UUID actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthPrincipal p) return p.userId();
        return null;
    }
}
