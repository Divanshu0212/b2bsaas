package com.salespipe.eventing;

import java.util.List;

/**
 * Kafka topic names per docs/plan/00-overview.md §5. Partition key (not encoded here —
 * it's the producer's {@code aggregate_id}) gives per-aggregate ordering.
 */
public final class Topics {

    public static final String DEAL_STAGE_CHANGED = "deal.stage.changed";
    public static final String LEAD_CREATED = "lead.created";
    public static final String EMAIL_EVENT_RECEIVED = "email.event.received";
    public static final String LEAD_SCORE_UPDATED = "lead.score.updated";
    public static final String ACTIVITY_LOGGED = "activity.logged";

    /** All topics that carry a canonical {@link EventEnvelope} and need a registered schema. */
    public static final List<String> ALL = List.of(
        DEAL_STAGE_CHANGED,
        LEAD_CREATED,
        EMAIL_EVENT_RECEIVED,
        LEAD_SCORE_UPDATED,
        ACTIVITY_LOGGED
    );

    /** Dead-letter topic name for a given source topic (see overview §5 table). */
    public static String dlqFor(String topic) {
        return topic + ".DLQ";
    }

    private Topics() {}
}
