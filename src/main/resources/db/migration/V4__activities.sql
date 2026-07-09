-- T2.5: activity timeline table (docs/plan/00-overview.md §4, phase-2 plan T2.5).
--
-- Migration version note: T2.3 already used V3 (V3__outbox_published_column.sql), so
-- this is V4 -- the next free integer in src/main/resources/db/migration/ at the time
-- this migration was authored. (T2.6's email-tracking migration is a separate, later
-- version -- see that migration file for its own numbering rationale; the two features
-- were built concurrently on this branch and each claimed its own next-free slot.)
--
-- Partitioned monthly by created_at (native Postgres RANGE partitioning), per overview
-- §4's "Partitioned tables (activities, email_events): monthly range partitions,
-- pg_partman or Flyway-managed" note.
--
-- Provisioning choice: pg_partman is not installed in the base Postgres image used here
-- (postgres:16-alpine, see PostgresRedisTestBase / docker-compose.yml) and adding it is
-- out of scope for this task. The pragmatic choice documented in the plan's own T2.5
-- wording is a Flyway-managed create_next_partition function: this migration defines
-- create_activities_partition(year, month), a small idempotent helper (CREATE TABLE IF
-- NOT EXISTS ... PARTITION OF ...) that ops/future migrations/a future @Scheduled job
-- can call again to provision further months. It is called here for the current month
-- plus the next 3 months so the table is immediately writable without a day-one gap.
-- A default/catch-all partition is also created so an insert for a not-yet-provisioned
-- month fails loudly into an unbounded partition instead of erroring outright -- ops can
-- detect rows landing there (unexpectedly large partition) as the signal that partition
-- provisioning has fallen behind, matching the plan's own "Risks/gotchas" call-out
-- ("Partition provisioning must run ahead of time -- a missing future partition = insert
-- failure. Automate it.").

CREATE TABLE activities (
    id            UUID NOT NULL,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    entity_type   TEXT NOT NULL,          -- polymorphic: 'lead' | 'deal' | 'contact'
    entity_id     UUID NOT NULL,
    -- activity_type is deliberately free-text, not a CHECK-constrained enum: the plan's
    -- example list (CALL|EMAIL|NOTE|MEETING|STAGE_CHANGE) is illustrative, and unlike
    -- email_events.event_type (overview §4, which DOES have a CHECK) the SQL sketch for
    -- `activities` shows no such constraint. Treating it as extensible lets new event
    -- types (e.g. this task's own lead.created -> LEAD_CREATED) be added by new
    -- consumers without a schema migration. See activity/domain/ActivityType.java for
    -- the enumerated Java-side convention (still just a String column value here).
    activity_type TEXT NOT NULL,
    payload       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Composite index per overview §4: (org_id, entity_type, entity_id, created_at DESC).
-- Created on the parent -- Postgres 11+ propagates it automatically to every existing
-- and future partition (each partition gets its own physical index of the same shape).
CREATE INDEX idx_activities_org_entity_created
    ON activities (org_id, entity_type, entity_id, created_at DESC);

-- GIN index on payload for ad-hoc querying inside the JSONB blob (overview §4).
CREATE INDEX idx_activities_payload_gin
    ON activities USING GIN (payload);

-- Idempotent monthly-partition provisioning function. Safe to call repeatedly (e.g. from
-- a future @Scheduled job or an ops runbook) for the same (year, month) -- CREATE TABLE
-- IF NOT EXISTS on the generated partition name is a no-op on repeat calls.
CREATE OR REPLACE FUNCTION create_activities_partition(p_year INT, p_month INT)
RETURNS void AS $$
DECLARE
    partition_start DATE := make_date(p_year, p_month, 1);
    partition_end   DATE := partition_start + INTERVAL '1 month';
    partition_name  TEXT := format('activities_%s', to_char(partition_start, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF activities FOR VALUES FROM (%L) TO (%L)',
        partition_name, partition_start, partition_end
    );
END;
$$ LANGUAGE plpgsql;

-- Provision the current month plus the next 3 months so the table is immediately
-- writable. Uses now() at migration-run time as the anchor (fresh table, no historical
-- data yet, per the task brief).
DO $$
DECLARE
    anchor DATE := date_trunc('month', now())::date;
    i INT;
BEGIN
    FOR i IN 0..3 LOOP
        PERFORM create_activities_partition(
            EXTRACT(YEAR FROM anchor + (i || ' months')::interval)::int,
            EXTRACT(MONTH FROM anchor + (i || ' months')::interval)::int
        );
    END LOOP;
END;
$$;

-- Catch-all default partition: any insert whose created_at doesn't fall into a
-- provisioned monthly partition lands here instead of failing outright. This trades
-- "loud failure" for "silent-but-detectable" (an unexpectedly large default partition
-- signals provisioning has fallen behind) -- acceptable for a fresh append-only table
-- where losing a write is worse than a temporarily mis-partitioned row. Ops/a future
-- scheduled job should periodically detach rows out of DEFAULT into a proper monthly
-- partition once provisioning catches up.
CREATE TABLE activities_default PARTITION OF activities DEFAULT;
