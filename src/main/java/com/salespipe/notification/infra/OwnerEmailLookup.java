package com.salespipe.notification.infra;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a user's email address, tenant-scoped, so the notification consumers can send
 * the owner an email (T4.4). Same org-scoped JDBC pattern as {@link DealOwnerLookup} /
 * {@link LeadOwnerLookup} — runs on a Kafka consumer thread, so an explicit {@code org_id}
 * predicate rather than the request-scoped Hibernate tenant filter.
 */
@Component
public class OwnerEmailLookup {

    private final JdbcTemplate jdbc;

    public OwnerEmailLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner's email, or {@code null} if the user id is null or not found in this org. */
    public String findEmail(UUID orgId, UUID userId) {
        if (userId == null) {
            return null;
        }
        return jdbc.query(
            "SELECT email FROM users WHERE org_id = ? AND id = ?",
            rs -> rs.next() ? rs.getString("email") : null,
            orgId, userId
        );
    }
}
