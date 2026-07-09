package com.salespipe.activity.domain;

/**
 * Convention constants for {@code activities.activity_type} (T2.5, docs/plan/00-overview.md
 * §4, phase-2 plan T2.5).
 *
 * <p><b>Not a JPA {@code @Enumerated} enum / not DB-{@code CHECK}-constrained.</b> The
 * plan's SQL sketch for {@code activities} lists an illustrative set (
 * {@code CALL|EMAIL|NOTE|MEETING|STAGE_CHANGE}) but, unlike {@code email_events.event_type}
 * (overview §4, which explicitly has a {@code CHECK (event_type IN (...))}), the
 * {@code activities} sketch has no such constraint. Read literally: {@code activity_type}
 * is meant to be an open, extensible free-text column, not a closed enum — new consumers
 * (this task's own {@code lead.created}, future ones like scoring or email-tracking
 * events feeding the timeline) can introduce new values without a schema migration.
 * {@link #forLeadCreated()} documents the specific choice made for a topic that doesn't
 * map cleanly onto the plan's illustrative list.
 */
public final class ActivityType {

    public static final String CALL = "CALL";
    public static final String EMAIL = "EMAIL";
    public static final String NOTE = "NOTE";
    public static final String MEETING = "MEETING";
    public static final String STAGE_CHANGE = "STAGE_CHANGE";

    /**
     * Value used for the {@code lead.created} consumer. {@code lead.created} doesn't map
     * cleanly onto CALL|EMAIL|NOTE|MEETING|STAGE_CHANGE — it's a system/lifecycle event,
     * not a sales activity a rep performed. Rather than force-fitting it into NOTE (a
     * generic bucket that would make the timeline's activity_type facet less useful for
     * filtering later), this introduces one additional, clearly-named extensible value:
     * {@code LEAD_CREATED}. This is consistent with treating the column as open/extensible
     * (see class javadoc) rather than a rigid enum.
     */
    public static final String LEAD_CREATED = "LEAD_CREATED";

    private ActivityType() {}
}
