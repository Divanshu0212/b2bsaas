-- T2.7: minimal in-app notifications table (docs/plan/00-overview.md §4, §9;
-- phase-2 plan T2.7: "Notification consumer stub for deal.stage.changed (full
-- notifications Phase 4-ish; minimal in-app `notifications` row now)").
--
-- Migration version note: V1-V3 are the outbox/init migrations, V4 (activities) and V5
-- (email_tracking) were claimed by T2.5/T2.6 respectively (see those migrations' own
-- version-note comments). V6 is therefore the next free integer in
-- src/main/resources/db/migration/ at the time this migration was authored.
--
-- Schema is exactly the overview §4 sketch:
--   notifications (id UUID PK, org_id, user_id, type, payload JSONB, read_at NULL, created_at)
-- Not partitioned -- unlike activities/email_events this is not called out in overview
-- §4's partitioning note, and full notification delivery/read-state/preferences is
-- explicitly Phase 4+ scope; keep this table minimal per the plan.
CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations(id),
    user_id    UUID,
    type       TEXT NOT NULL,
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lookup pattern is "a user's (unread) notifications within their org" -- composite
-- index covers the org-scoped, user-scoped, recency-ordered read path.
CREATE INDEX idx_notifications_org_user_created
    ON notifications (org_id, user_id, created_at DESC);
