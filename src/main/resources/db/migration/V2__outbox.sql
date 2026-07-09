CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL REFERENCES organizations(id),
    aggregate_type TEXT NOT NULL,
    aggregate_id   TEXT NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    trace_id       TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
    -- NOTE: no `published` column — this table is CDC-sourced (Debezium reads the WAL).
    -- The polling-relay fallback (T2.3) ships a separate migration variant that adds
    -- `published BOOLEAN DEFAULT false`, guarded by a config profile; not this one.
);
CREATE INDEX idx_outbox_events_org ON outbox_events(org_id, created_at DESC);

CREATE TABLE processed_events (
    consumer_group TEXT NOT NULL,
    event_id       UUID NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
