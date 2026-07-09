package com.salespipe.scoring.infra;

import com.salespipe.scoring.client.ScoreRequest;
import com.salespipe.scoring.domain.LeadFeatures;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link ScoreRequest} sent to the Python service from the lead's persisted
 * feature row plus a few live reads (T3.4). {@code days_since_last_activity} is derived
 * here at score time from {@code lead_features.last_activity_at} rather than stored (a
 * stored day-count is stale the moment it is written — see {@code V7} comment).
 *
 * <p>Text features (lead {@code raw_notes}) and the categorical {@code industry}/{@code
 * source} come from {@code leads}/{@code accounts} via org-scoped JDBC (boundary-clean,
 * same as {@link FeatureLookup}).
 */
@Component
public class ScoringFeatureAssembler {

    private final JdbcTemplate jdbc;

    public ScoringFeatureAssembler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ScoreRequest assemble(UUID orgId, UUID leadId, LeadFeatures features) {
        Map<String, Object> structured = new HashMap<>();
        structured.put("email_open_count", features != null ? features.getEmailOpenCount() : 0);
        structured.put("email_click_count", features != null ? features.getEmailClickCount() : 0);
        structured.put("days_since_last_activity", daysSince(features != null ? features.getLastActivityAt() : null));
        structured.put("deal_velocity_days",
            features != null && features.getDealVelocityDays() != null ? features.getDealVelocityDays() : null);
        structured.put("company_size_bucket", features != null ? features.getCompanySizeBucket() : null);

        LeadContext ctx = leadContext(orgId, leadId);
        structured.put("industry", ctx.industry());
        structured.put("source", ctx.source());

        List<String> text = new ArrayList<>();
        if (ctx.rawNotes() != null && !ctx.rawNotes().isBlank()) {
            text.add(ctx.rawNotes());
        }
        return new ScoreRequest(leadId.toString(), text, structured);
    }

    private Double daysSince(OffsetDateTime last) {
        if (last == null) {
            return null;
        }
        return (double) ChronoUnit.DAYS.between(last, OffsetDateTime.now());
    }

    private LeadContext leadContext(UUID orgId, UUID leadId) {
        return jdbc.query(
            "SELECT l.raw_notes, l.source, a.industry "
                + "FROM leads l LEFT JOIN accounts a ON a.id = l.account_id "
                + "WHERE l.org_id = ? AND l.id = ?",
            rs -> rs.next()
                ? new LeadContext(rs.getString("raw_notes"), rs.getString("source"), rs.getString("industry"))
                : new LeadContext(null, null, null),
            orgId, leadId
        );
    }

    private record LeadContext(String rawNotes, String source, String industry) {}
}
