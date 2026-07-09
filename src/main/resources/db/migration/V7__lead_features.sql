-- T3.1: async-maintained feature store for lead scoring (docs/plan/00-overview.md §4,
-- phase-3 plan T3.1). One row per lead, updated by FeatureAggregationConsumer as
-- email/activity/stage events flow in — the feature writes go through the idempotent
-- consumer framework so this is a consistency-managed store, not a silent dual-write
-- (overview change #3).
--
-- Migration version note: V1-V3 outbox/init, V4 activities, V5 email_tracking, V6
-- notifications. V7 is the next free integer at authoring time. The phase-3 plan's task
-- text names this "V5__lead_features.sql", but V5 was already claimed by T2.6's
-- email_tracking migration; the file version must be monotonic for Flyway, so it lands
-- at V7. Table name and columns are unchanged from the plan.
CREATE TABLE lead_features (
    lead_id                  UUID PRIMARY KEY REFERENCES leads(id),
    org_id                   UUID NOT NULL REFERENCES organizations(id),
    email_open_count         INT  NOT NULL DEFAULT 0,
    email_click_count        INT  NOT NULL DEFAULT 0,
    -- Derived-at-read features. days_since_last_activity is a function of the newest
    -- activity timestamp, so we persist that timestamp and let the scoring path compute
    -- the delta at score time (a stored day-count would be stale the moment it is
    -- written). activity_count_30d likewise recomputed from last_activity_at + the
    -- running counter at read; here we keep the raw signals the consumer can maintain
    -- monotonically from the event stream.
    last_activity_at         TIMESTAMPTZ,
    activity_count_30d       INT  NOT NULL DEFAULT 0,
    deal_velocity_days       NUMERIC(8,2),
    company_size_bucket      TEXT,
    feature_version          INT  NOT NULL DEFAULT 1,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Consumer updates are always keyed by (org_id, lead_id); PK already covers lead_id,
-- this covers the org-scoped batch read the recompute scheduler does.
CREATE INDEX idx_lead_features_org ON lead_features (org_id);
