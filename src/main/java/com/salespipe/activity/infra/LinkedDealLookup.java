package com.salespipe.activity.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a lead's org (tenant-scoped existence check) and its linked deal ids, for
 * {@code TimelineController}'s "merged feed" (T2.5) — see that class's javadoc for the
 * interpretation this supports.
 *
 * <p>Queries the {@code leads}/{@code deals} tables directly via {@link JdbcTemplate}
 * rather than through {@code crmcore}'s {@code LeadRepository} or {@code pipeline}'s
 * {@code DealRepository} JPA entities. Two reasons: (1) T2.5 is explicitly scoped to
 * stay out of {@code pipeline/} (a concurrent agent works there; that module's write
 * paths are a separate task, T2.7) and {@code DealRepository} has no existing {@code
 * findByLeadId}-shaped finder to reuse without editing that module; (2) Spring
 * Modulith's default module-boundary verification (see {@code ModuleBoundaryTest})
 * treats every package other than a module's root as internal — reaching into {@code
 * crmcore.infra}/{@code crmcore.domain} or {@code pipeline.infra}/{@code
 * pipeline.domain} from the {@code activity} module would violate that boundary (no
 * {@code @NamedInterface} is exposed on those subpackages). A narrow, explicitly
 * org-scoped read-only SQL query here avoids editing another module's package-info
 * just to open an interface for one read.
 *
 * <p>Explicit {@code org_id} predicate on every query (not relying on Hibernate's
 * request-scoped {@code @Filter}, which this class deliberately does not go through)
 * for the same reason {@code PollingRelay} does its own {@code org_id} filtering when
 * reading a table via plain JDBC — see that class's javadoc.
 */
@Component
public class LinkedDealLookup {

    private final JdbcTemplate jdbc;

    public LinkedDealLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code true} if a lead with this id exists within the given org (tenant-scoped existence check). */
    public boolean leadExists(UUID orgId, UUID leadId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM leads WHERE org_id = ? AND id = ?",
            Integer.class, orgId, leadId
        );
        return count != null && count > 0;
    }

    /** Deal ids (within the given org) whose {@code lead_id} is {@code leadId}. */
    public List<UUID> findDealIdsForLead(UUID orgId, UUID leadId) {
        return jdbc.query(
            "SELECT id FROM deals WHERE org_id = ? AND lead_id = ?",
            (rs, rowNum) -> UUID.fromString(rs.getString("id")),
            orgId, leadId
        );
    }
}
