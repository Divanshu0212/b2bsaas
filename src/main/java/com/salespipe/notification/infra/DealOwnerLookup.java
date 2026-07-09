package com.salespipe.notification.infra;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a deal's {@code owner_id}, tenant-scoped, for {@link
 * com.salespipe.notification.consumer.DealStageChangedNotificationConsumer} — the
 * notification stub notifies the deal's owner (T2.7 plan text: "e.g. notifying the
 * deal's owner").
 *
 * <p>Plain {@link JdbcTemplate} query against {@code deals} directly, not {@code
 * pipeline}'s {@code DealRepository} JPA entity — same reasoning as {@code
 * activity/infra/LinkedDealLookup}: Spring Modulith's module-boundary verification
 * treats {@code pipeline.infra}/{@code pipeline.domain} as internal to that module (no
 * {@code @NamedInterface} exposed), so a narrow, explicitly org-scoped read-only query
 * here avoids reaching across the module boundary for one lookup. Explicit {@code
 * org_id} predicate rather than relying on Hibernate's request-scoped {@code @Filter}
 * for the same reason — this runs inside a Kafka consumer thread, not a request thread,
 * so there is no populated {@code TenantContext} to filter through anyway.
 */
@Component
public class DealOwnerLookup {

    private final JdbcTemplate jdbc;

    public DealOwnerLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The {@code owner_id} of the given deal within the given org, or {@code null} if not found/unset. */
    public UUID findOwnerId(UUID orgId, UUID dealId) {
        return jdbc.query(
            "SELECT owner_id FROM deals WHERE org_id = ? AND id = ?",
            rs -> rs.next() ? (UUID) rs.getObject("owner_id") : null,
            orgId, dealId
        );
    }
}
