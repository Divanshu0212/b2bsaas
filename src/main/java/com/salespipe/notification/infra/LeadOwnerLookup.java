package com.salespipe.notification.infra;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a lead's {@code owner_id}, tenant-scoped, for the hot-lead notification
 * (T3.5). Same boundary-clean org-scoped JDBC pattern as {@link DealOwnerLookup} — runs
 * on a Kafka consumer thread, so explicit {@code org_id} predicate, no reliance on the
 * request-scoped Hibernate filter.
 */
@Component
public class LeadOwnerLookup {

    private final JdbcTemplate jdbc;

    public LeadOwnerLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID findOwnerId(UUID orgId, UUID leadId) {
        return jdbc.query(
            "SELECT owner_id FROM leads WHERE org_id = ? AND id = ?",
            rs -> rs.next() ? (UUID) rs.getObject("owner_id") : null,
            orgId, leadId
        );
    }
}
