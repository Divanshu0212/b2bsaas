package com.salespipe.scoring.infra;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the denormalized {@code leads.current_score} cache (T3.4). {@code leads} lives
 * in {@code crmcore}; updating it from {@code scoring} via a narrow, explicitly
 * org-scoped JDBC statement follows the same boundary-clean approach as {@code
 * FeatureLookup}/{@code activity.infra.LinkedDealLookup} (no cross-module JPA reach).
 *
 * <p>{@code lead_scores} is the source-of-truth history; {@code current_score} is a
 * read-cache for list views so the UI does not need a subquery per lead.
 */
@Component
public class LeadScoreWriter {

    private final JdbcTemplate jdbc;

    public LeadScoreWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void updateCurrentScore(UUID orgId, UUID leadId, BigDecimal score) {
        jdbc.update(
            "UPDATE leads SET current_score = ?, updated_at = now() WHERE org_id = ? AND id = ?",
            score, orgId, leadId
        );
    }
}
