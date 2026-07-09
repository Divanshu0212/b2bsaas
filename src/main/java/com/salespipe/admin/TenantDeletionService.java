package com.salespipe.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR tenant hard-delete (T4.7, overview change #12): removes every row belonging to an
 * {@code org_id} across all org-scoped tables, so deleting a tenant leaves zero trace of
 * its data anywhere.
 *
 * <p>Tables are deleted in an explicit, FK-safe order (children before parents) held in a
 * constant list rather than derived from the catalog — so adding a new org-scoped table is
 * a conscious edit here, and a forgotten table can't silently leave orphaned tenant PII.
 * {@code processed_events} is intentionally absent: it is the only table with no
 * {@code org_id} (it's keyed on {@code (consumer_group, event_id)}), so there is nothing
 * tenant-scoped to delete from it.
 *
 * <p>The partitioned tables ({@code activities}, {@code email_events}) are deleted by
 * {@code org_id} predicate here, which cascades across their partitions; whole-partition
 * dropping for time-based retention is a separate concern ({@code RetentionJob}).
 */
@Service
public class TenantDeletionService {

    private static final Logger log = LoggerFactory.getLogger(TenantDeletionService.class);

    /**
     * Org-scoped tables in FK-safe delete order (leaf/child tables first, {@code
     * organizations} last). Every table whose rows carry an {@code org_id}.
     */
    public static final List<String> ORG_SCOPED_TABLES_DELETE_ORDER = List.of(
        // leaves that reference leads/deals/emails/users
        "refresh_tokens",
        "lead_scores",
        "lead_features",
        "notifications",
        "activities",
        "email_events",
        "emails",
        "deal_stage_history",
        "audit_log",
        "outbox_events",
        // mid-level: deals -> leads/deal_stages; leads -> accounts/contacts
        "deals",
        "deal_stages",
        "leads",
        "contacts",
        "accounts",
        "users"
        // organizations is deleted separately by its PK `id` (it has no org_id column).
    );

    private final JdbcTemplate jdbc;

    public TenantDeletionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Hard-deletes all data for {@code orgId} across every org-scoped table, in one
     * transaction so it's all-or-nothing. Returns a per-table count of rows removed.
     */
    @Transactional
    public Map<String, Integer> hardDelete(UUID orgId) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String table : ORG_SCOPED_TABLES_DELETE_ORDER) {
            int rows = jdbc.update("DELETE FROM " + table + " WHERE org_id = ?", orgId);
            deleted.put(table, rows);
        }
        // The organizations row itself has PK `id`, not an org_id column — delete last,
        // after every child row referencing it is gone.
        deleted.put("organizations", jdbc.update("DELETE FROM organizations WHERE id = ?", orgId));
        log.info("GDPR hard-delete for org_id={} removed rows={}", orgId, deleted);
        return deleted;
    }
}
