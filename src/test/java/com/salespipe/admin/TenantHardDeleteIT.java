package com.salespipe.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.support.PostgresRedisTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T4.7: hard-deleting a tenant leaves zero rows for that org_id anywhere, and touches no
 * other tenant's data. Seeds two orgs across a representative set of FK-linked tables, then
 * deletes one.
 */
class TenantHardDeleteIT extends PostgresRedisTestBase {

    @Autowired TenantDeletionService service;
    @Autowired JdbcTemplate jdbc;

    private UUID seedOrg(String name) {
        UUID org = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, 'FREE')", org, name);

        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, org_id, email, password_hash, role) VALUES (?, ?, ?, 'x', 'ADMIN')",
            user, org, "owner-" + org + "@x.com");

        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, org_id, name) VALUES (?, ?, 'Acct')", account, org);

        UUID lead = UUID.randomUUID();
        jdbc.update("INSERT INTO leads (id, org_id, account_id, status, owner_id) VALUES (?, ?, ?, 'NEW', ?)",
            lead, org, account, user);

        // notifications + outbox_events are org-scoped and don't need deep FKs.
        jdbc.update("INSERT INTO notifications (id, org_id, user_id, type, payload) VALUES (?, ?, ?, 'X', '{}')",
            UUID.randomUUID(), org, user);
        jdbc.update(
            "INSERT INTO outbox_events (id, org_id, aggregate_type, aggregate_id, event_type, payload) " +
                "VALUES (?, ?, 'lead', ?, 'lead.created', '{}'::jsonb)",
            UUID.randomUUID(), org, lead.toString());

        return org;
    }

    private int totalRowsForOrg(UUID org) {
        int total = 0;
        for (String table : TenantDeletionService.ORG_SCOPED_TABLES_DELETE_ORDER) {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE org_id = ?", Integer.class, org);
            total += n == null ? 0 : n;
        }
        Integer orgRow = jdbc.queryForObject(
            "SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, org);
        return total + (orgRow == null ? 0 : orgRow);
    }

    @Test
    void hardDeleteRemovesAllRowsForOrgAndLeavesOthersIntact() {
        UUID orgA = seedOrg("Org A");
        UUID orgB = seedOrg("Org B");

        assertThat(totalRowsForOrg(orgA)).isGreaterThan(0);
        int bBefore = totalRowsForOrg(orgB);
        assertThat(bBefore).isGreaterThan(0);

        service.hardDelete(orgA);

        // Zero rows anywhere for A, including the organizations row itself.
        assertThat(totalRowsForOrg(orgA)).isZero();
        // B untouched.
        assertThat(totalRowsForOrg(orgB)).isEqualTo(bBefore);
    }
}
