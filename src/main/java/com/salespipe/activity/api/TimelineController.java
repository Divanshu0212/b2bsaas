package com.salespipe.activity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.activity.api.dto.ActivityResponse;
import com.salespipe.activity.domain.Activity;
import com.salespipe.activity.infra.ActivityRepository;
import com.salespipe.activity.infra.LinkedDealLookup;
import com.salespipe.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /leads/{id}/timeline} (T2.5, docs/plan/00-overview.md, phase-2 plan T2.5).
 *
 * <p><b>"Merged" interpretation.</b> The plan's accept line is "timeline endpoint
 * returns merged, paginated, tenant-scoped feed". Since every {@code activity/consumer}
 * appends to the single shared {@code activities} table, the store itself is already
 * the merge point across event sources (stage changes, lead creation, ad-hoc logged
 * activities). The remaining question is merge across *entities*: a sales rep looking
 * at a lead's timeline plausibly wants to see that lead's own activities AND the
 * activities of any deal(s) that grew out of it (e.g. its STAGE_CHANGE history) in one
 * feed — otherwise "the lead's timeline" would go silent the moment the lead converts
 * into a deal, which defeats the point of a timeline. This controller therefore
 * resolves the lead's linked deal ids (via {@link LinkedDealLookup}) and queries {@link
 * ActivityRepository#findTimeline} for {@code entity_id in (leadId, ...dealIds)},
 * ordered by {@code created_at DESC}, in one paginated query.
 *
 * <p><b>Tenant scoping.</b> The lead-existence check ({@link
 * LinkedDealLookup#leadExists}) is explicitly {@code org_id}-scoped SQL (see that
 * class's javadoc for why this controller queries the {@code leads}/{@code deals}
 * tables directly rather than through {@code crmcore}'s/{@code pipeline}'s JPA
 * repositories) so a cross-tenant lead id 404s before any activities are read. The
 * linked-deal lookup and the activities query are each explicitly org-scoped too (see
 * {@link LinkedDealLookup} and {@link ActivityRepository#findTimeline}'s
 * Hibernate-filter note) so no cross-tenant row can leak in even if a lead id were
 * somehow guessed correctly for the wrong org (defense in depth, not reliance on a
 * single check).
 */
@RestController
public class TimelineController {

    private final LinkedDealLookup linkedDeals;
    private final ActivityRepository activities;
    private final TenantContext tenant;
    private final ObjectMapper objectMapper;

    public TimelineController(
        LinkedDealLookup linkedDeals,
        ActivityRepository activities,
        TenantContext tenant,
        ObjectMapper objectMapper
    ) {
        this.linkedDeals = linkedDeals;
        this.activities = activities;
        this.tenant = tenant;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/leads/{id}/timeline")
    public Page<ActivityResponse> timeline(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (!linkedDeals.leadExists(tenant.getOrgId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
        }

        List<UUID> entityIds = new ArrayList<>();
        entityIds.add(id);
        entityIds.addAll(linkedDeals.findDealIdsForLead(tenant.getOrgId(), id));

        return activities.findTimeline(entityIds, PageRequest.of(page, size))
            .map(this::toResponse);
    }

    private ActivityResponse toResponse(Activity a) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(a.getPayload());
        } catch (Exception e) {
            payload = objectMapper.createObjectNode();
        }
        return new ActivityResponse(
            a.getId().getId(),
            a.getEntityType(),
            a.getEntityId(),
            a.getActivityType(),
            payload,
            a.getCreatedBy(),
            a.getCreatedAt()
        );
    }
}
