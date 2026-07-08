package com.salespipe.common.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Opens (and later closes) a request-bound {@link EntityManager} and enables the
 * Hibernate {@code tenantFilter} on it for the current tenant.
 *
 * <p>Spring Boot's built-in "open EntityManager in view" support
 * ({@code OpenEntityManagerInViewInterceptor}) is a Spring MVC {@code HandlerInterceptor},
 * which only runs once {@code DispatcherServlet} dispatches to a handler — i.e. strictly
 * <em>after</em> the entire servlet {@code Filter} chain, including Spring Security's
 * {@code FilterChainProxy} that {@link TenantFilter} is wired into. Relying on it would
 * mean the tenant filter gets enabled on a throwaway, per-call session (see the
 * {@code SharedEntityManagerCreator} proxy semantics), never the session Spring Data JPA
 * actually runs queries against — so cross-tenant rows would leak through unfiltered.
 *
 * <p>This class instead performs the binding itself, mirroring exactly what
 * {@code OpenEntityManagerInViewInterceptor} does (open an EntityManager, wrap it in an
 * {@link EntityManagerHolder}, bind it via {@link TransactionSynchronizationManager}), but
 * from {@link TenantFilter} so it happens before the request reaches any controller or
 * repository call.
 */
@Component
public class TenantFilterAspect {

    private final EntityManagerFactory emf;
    private final TenantContext tenantContext;

    public TenantFilterAspect(EntityManagerFactory emf, TenantContext tenantContext) {
        this.emf = emf;
        this.tenantContext = tenantContext;
    }

    /**
     * Opens a request-bound EntityManager (if one isn't already bound) and enables the
     * tenant filter on it. Returns {@code true} if this call opened the EntityManager
     * (and is therefore responsible for closing it via {@link #close(boolean)}).
     */
    public boolean enable() {
        if (RequestContextHolder.getRequestAttributes() == null) return false;
        if (!tenantContext.isSet()) return false;
        if (TransactionSynchronizationManager.hasResource(emf)) {
            // Already bound (e.g. re-entrant dispatch) - just (re)enable the filter on it.
            EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(emf);
            enableOn(holder.getEntityManager());
            return false;
        }

        EntityManager em = emf.createEntityManager();
        enableOn(em);
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        return true;
    }

    /** Unbind and close the EntityManager opened by {@link #enable()}. */
    public void close(boolean opened) {
        if (!opened) return;
        EntityManagerHolder holder =
            (EntityManagerHolder) TransactionSynchronizationManager.unbindResource(emf);
        EntityManagerFactoryUtils.closeEntityManager(holder.getEntityManager());
    }

    private void enableOn(EntityManager em) {
        em.unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("orgId", tenantContext.getOrgId());
    }
}
