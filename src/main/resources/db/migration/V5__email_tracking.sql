-- T2.6: email tracking tables (docs/plan/00-overview.md §4, phase-2 plan T2.6).
--
-- Migration version note: at the time this migration was authored, V1-V3 were the
-- outbox/init migrations and the T2.5 agent (working concurrently on this same branch,
-- under activity/) had already claimed V4 (V4__activities.sql) for the activities
-- table. V5 was therefore the next free integer under src/main/resources/db/migration/.
-- Both tasks were built in parallel against the same branch tip; if the other agent's
-- work lands with a different V4/V5 assignment than expected by the time this branch is
-- integrated, whoever merges resolves the numbering collision -- this comment documents
-- what number was actually used here (V5) so that's traceable.
--
-- emails: outbound tracked email metadata + anti-forgery token (overview §4). No
-- TenantEntity/Hibernate @Filter involvement here on purpose -- see
-- emailtracking/domain/Email.java and TrackingController for why: the read/write paths
-- that touch this table at request time (the open/click pixel + webhook endpoints) are
-- unauthenticated public HTTP endpoints hit by mail clients / provider webhooks with no
-- JWT and therefore no populated TenantContext, so org scoping there is done by
-- explicit org_id predicates in application code, never the request-scoped Hibernate
-- filter used by the authenticated CRUD paths.
CREATE TABLE emails (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    deal_id       UUID,
    lead_id       UUID,
    subject       TEXT,
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    tracking_id   UUID NOT NULL,             -- opaque public id, embedded in pixel/click URLs
    tracking_sig  TEXT NOT NULL              -- HMAC(tracking_id [+org_id], app secret); see TrackingSigner
);
CREATE UNIQUE INDEX idx_emails_tracking_id ON emails(tracking_id);
CREATE INDEX idx_emails_org ON emails(org_id);

-- email_events: append-only OPENED/CLICKED/BOUNCED log, PARTITIONED BY RANGE(occurred_at)
-- (overview §4: "Partitioned tables (activities, email_events): monthly range
-- partitions"). Same provisioning approach as V4__activities.sql's
-- create_activities_partition -- pg_partman isn't installed in the base postgres:16-alpine
-- image used by this repo (see PostgresRedisTestBase / docker-compose.yml) and adding it
-- is out of scope here, so this migration defines its own idempotent
-- create_email_events_partition(year, month) function, called for the current month plus
-- the next 3 months, plus a DEFAULT catch-all partition for the same "loud-ish but not
-- write-failing" reason documented in V4. The function is intentionally not shared code
-- with activities' version (duplicated rather than factored into a generic
-- create_monthly_partition(table, year, month) helper) -- each partitioned table's
-- migration owns its own provisioning function so the two features stay independently
-- deployable/revertable while built concurrently on this branch.
CREATE TABLE email_events (
    id           UUID NOT NULL,
    org_id       UUID NOT NULL REFERENCES organizations(id),
    email_id     UUID NOT NULL REFERENCES emails(id),
    event_type   TEXT NOT NULL CHECK (event_type IN ('OPENED', 'CLICKED', 'BOUNCED')),
    ip           TEXT,
    user_agent   TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Lookup pattern for this table is "events for one email" and "events for one org" --
-- composite index covers both the FK join and org-scoped scans, propagated automatically
-- to every partition (Postgres 11+).
CREATE INDEX idx_email_events_org_email_occurred
    ON email_events (org_id, email_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION create_email_events_partition(p_year INT, p_month INT)
RETURNS void AS $$
DECLARE
    partition_start DATE := make_date(p_year, p_month, 1);
    partition_end   DATE := partition_start + INTERVAL '1 month';
    partition_name  TEXT := format('email_events_%s', to_char(partition_start, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF email_events FOR VALUES FROM (%L) TO (%L)',
        partition_name, partition_start, partition_end
    );
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    anchor DATE := date_trunc('month', now())::date;
    i INT;
BEGIN
    FOR i IN 0..3 LOOP
        PERFORM create_email_events_partition(
            EXTRACT(YEAR FROM anchor + (i || ' months')::interval)::int,
            EXTRACT(MONTH FROM anchor + (i || ' months')::interval)::int
        );
    END LOOP;
END;
$$;

CREATE TABLE email_events_default PARTITION OF email_events DEFAULT;
