package com.salespipe.scoring.infra;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Narrow, explicitly org-scoped reads the scoring module needs from other modules'
 * tables ({@code deals}, {@code accounts}, {@code deal_stages}). Same rationale as
 * {@code activity.infra.LinkedDealLookup}: reaching into {@code pipeline}/{@code
 * crmcore} JPA entities would cross Spring Modulith boundaries (no {@code
 * @NamedInterface} exposed on those subpackages), so a plain-JDBC read with an explicit
 * {@code org_id} predicate on every query is the boundary-clean option.
 */
@Component
public class FeatureLookup {

    private final JdbcTemplate jdbc;

    public FeatureLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The lead's account employee_count bucketed into a coarse size band, or null if unknown. */
    public String companySizeBucket(UUID orgId, UUID leadId) {
        Integer employees = jdbc.query(
            "SELECT a.employee_count FROM leads l JOIN accounts a ON a.id = l.account_id "
                + "WHERE l.org_id = ? AND l.id = ?",
            rs -> rs.next() ? (Integer) rs.getObject("employee_count") : null,
            orgId, leadId
        );
        return bucketOf(employees);
    }

    /**
     * Deal velocity in days for the lead's most-recently-changed deal: time from deal
     * creation to when it last entered its current stage. A rough "how fast is this deal
     * moving" signal; null if the lead has no deal yet.
     */
    public java.math.BigDecimal dealVelocityDays(UUID orgId, UUID leadId) {
        return jdbc.query(
            "SELECT EXTRACT(EPOCH FROM (entered_stage_at - created_at)) / 86400.0 AS days "
                + "FROM deals WHERE org_id = ? AND lead_id = ? "
                + "ORDER BY updated_at DESC LIMIT 1",
            rs -> rs.next() ? rs.getBigDecimal("days") : null,
            orgId, leadId
        );
    }

    /** The lead_id a deal is attached to (org-scoped), or null if the deal has no lead / is cross-tenant. */
    public UUID leadIdForDeal(UUID orgId, UUID dealId) {
        return jdbc.query(
            "SELECT lead_id FROM deals WHERE org_id = ? AND id = ?",
            rs -> {
                if (!rs.next()) return null;
                String v = rs.getString("lead_id");
                return v != null ? UUID.fromString(v) : null;
            },
            orgId, dealId
        );
    }

    /** Newest activity timestamp for a lead (across its own + linked deals' activities), or null. */
    public OffsetDateTime lastActivityAt(UUID orgId, UUID leadId) {
        return jdbc.query(
            "SELECT max(created_at) AS ts FROM activities "
                + "WHERE org_id = ? AND entity_id = ?",
            rs -> rs.next() ? rs.getObject("ts", OffsetDateTime.class) : null,
            orgId, leadId
        );
    }

    static String bucketOf(Integer employees) {
        if (employees == null) return null;
        if (employees <= 10) return "1-10";
        if (employees <= 50) return "11-50";
        if (employees <= 200) return "51-200";
        if (employees <= 1000) return "201-1000";
        return "1000+";
    }
}
