package com.salespipe.activity.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.activity.domain.Activity;
import com.salespipe.activity.infra.ActivityRepository;
import com.salespipe.eventing.consumer.InboundEvent;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared "append one timeline row" helper used by every {@code activity/consumer/*}
 * handler (T2.5). Centralizes the {@link InboundEvent} → {@link Activity} mapping so
 * each topic-specific consumer only needs to supply the {@code activity_type} value —
 * see {@code ActivityType} for the convention on that column.
 *
 * <p>Runs inside {@link com.salespipe.eventing.consumer.IdempotentConsumer}'s dedupe
 * transaction (called from each consumer's {@code handle(InboundEvent)} override), so
 * the insert here shares atomicity with the {@code processed_events} dedupe row per the
 * framework's contract.
 */
@Component
public class ActivityRecorder {

    private final ActivityRepository activities;
    private final ObjectMapper objectMapper;

    public ActivityRecorder(ActivityRepository activities, ObjectMapper objectMapper) {
        this.activities = activities;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends one {@code activities} row. {@code entityType}/{@code entityId} identify
     * the polymorphic owner of the timeline entry (e.g. {@code "deal"}/{@code deal_id}
     * or {@code "lead"}/{@code lead_id}); the full inbound event payload is stored
     * verbatim in {@code activities.payload} so no information the producer sent is
     * lost, even if this consumer only reads a subset of it for {@code activity_type}
     * selection.
     */
    public void record(InboundEvent event, String entityType, UUID entityId, String activityType) {
        JsonNode payload = event.payload() != null ? event.payload() : objectMapper.createObjectNode();
        UUID createdBy = extractActorId(payload);

        Activity activity = new Activity(
            UUID.randomUUID(),
            UUID.fromString(event.orgId()),
            entityType,
            entityId,
            activityType,
            payload.toString(),
            createdBy
        );
        activities.save(activity);
    }

    /**
     * Best-effort extraction of an actor/user id from the payload for {@code
     * activities.created_by}. Producers (T2.7, not yet wired at the time this consumer
     * was written) are not guaranteed to include this field on every topic, so this is
     * deliberately tolerant: missing or unparsable -> {@code null}, never a handler
     * failure (a malformed {@code created_by} must not poison-message an otherwise-valid
     * timeline event into the DLQ).
     */
    private UUID extractActorId(JsonNode payload) {
        JsonNode actor = payload.path("actorId");
        if (actor.isMissingNode() || actor.isNull()) {
            actor = payload.path("changedBy");
        }
        if (actor.isMissingNode() || actor.isNull() || !actor.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(actor.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
