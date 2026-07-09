package com.salespipe.scoring.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.scoring.domain.LeadFeatures;
import com.salespipe.scoring.infra.FeatureLookup;
import com.salespipe.scoring.infra.LeadFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared "apply one event to the lead's feature row" logic used by the three feature
 * aggregation consumers (email / activity / deal-stage). Centralizes the get-or-create
 * of {@code lead_features} and the per-event-type mutation so each topic consumer only
 * declares which topic/group it binds (T3.1).
 *
 * <p>Runs inside {@link com.salespipe.eventing.consumer.IdempotentConsumer}'s dedupe
 * transaction (called from each consumer's {@code handle}); the counter increments here
 * therefore share atomicity with the {@code processed_events} row, so a redelivered
 * event cannot double-count — this is exactly the T3.1 accept criterion ("an email
 * OPENED event increments email_open_count exactly once even on redelivery").
 */
@Component
public class FeatureUpdater {

    private final LeadFeaturesRepository features;
    private final FeatureLookup lookup;

    public FeatureUpdater(LeadFeaturesRepository features, FeatureLookup lookup) {
        this.features = features;
        this.lookup = lookup;
    }

    /** email.event.received -> increment OPENED/CLICKED counters + refresh last-activity. */
    public void applyEmailEvent(InboundEvent event) {
        UUID orgId = UUID.fromString(event.orgId());
        UUID leadId = leadIdOf(event, "leadId");
        LeadFeatures row = getOrCreate(orgId, leadId);

        String type = text(event.payload(), "eventType");
        if ("OPENED".equals(type)) {
            row.incrementEmailOpenCount();
        } else if ("CLICKED".equals(type)) {
            row.incrementEmailClickCount();
        }
        // BOUNCED is intentionally not a positive-engagement signal; recorded elsewhere.
        refreshDerived(row, orgId, leadId, true);
    }

    /** activity.logged -> bump 30d activity count + last-activity + recompute velocity/size. */
    public void applyActivityEvent(InboundEvent event) {
        UUID orgId = UUID.fromString(event.orgId());
        // activity.logged is keyed by entity_id; only lead-entity activities feed a lead's
        // features. Deal activities reach features via deal.stage.changed velocity below.
        if (!"lead".equals(text(event.payload(), "entityType"))) {
            return;
        }
        UUID leadId = leadIdOf(event, "entityId");
        LeadFeatures row = getOrCreate(orgId, leadId);
        row.incrementActivityCount30d();
        refreshDerived(row, orgId, leadId, true);
    }

    /** deal.stage.changed -> recompute deal_velocity_days for the deal's lead. */
    public void applyDealStageEvent(InboundEvent event) {
        UUID orgId = UUID.fromString(event.orgId());
        UUID leadId = resolveLeadForDeal(orgId, UUID.fromString(event.aggregateId()));
        if (leadId == null) {
            return; // deal has no lead attached — no lead features to move.
        }
        LeadFeatures row = getOrCreate(orgId, leadId);
        refreshDerived(row, orgId, leadId, false);
    }

    private void refreshDerived(LeadFeatures row, UUID orgId, UUID leadId, boolean touchActivity) {
        if (touchActivity) {
            row.setLastActivityAt(OffsetDateTime.now());
        } else {
            OffsetDateTime last = lookup.lastActivityAt(orgId, leadId);
            if (last != null) {
                row.setLastActivityAt(last);
            }
        }
        row.setDealVelocityDays(lookup.dealVelocityDays(orgId, leadId));
        row.setCompanySizeBucket(lookup.companySizeBucket(orgId, leadId));
        row.touch();
        features.save(row);
    }

    private LeadFeatures getOrCreate(UUID orgId, UUID leadId) {
        return features.findByOrgAndLead(orgId, leadId)
            .orElseGet(() -> new LeadFeatures(leadId, orgId));
    }

    private UUID resolveLeadForDeal(UUID orgId, UUID dealId) {
        return lookup.leadIdForDeal(orgId, dealId);
    }

    private static UUID leadIdOf(InboundEvent event, String payloadField) {
        String id = text(event.payload(), payloadField);
        return id != null ? UUID.fromString(id) : UUID.fromString(event.aggregateId());
    }

    private static String text(JsonNode payload, String field) {
        if (payload == null) return null;
        JsonNode n = payload.path(field);
        return n.isMissingNode() || n.isNull() || !n.isTextual() ? null : n.asText();
    }
}
