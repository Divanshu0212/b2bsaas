-- T2.3: polling-relay fallback support.
--
-- The primary outbox relay is Debezium CDC (reads the WAL directly; V2__outbox.sql's
-- `outbox_events` intentionally shipped with no `published` column for that path).
--
-- Flyway migrations are not profile-conditional -- they always run in order against
-- whatever database they're pointed at, so "ship both migrations guarded by profile"
-- (plan wording, T2.3) is implemented here as: add the column unconditionally in this
-- migration (it costs nothing on the CDC path -- Debezium's outbox-event-router SMT
-- never reads or writes it), and gate only the *behavior* that uses it -- the
-- `PollingRelay` `@Scheduled` bean -- behind `app.eventing.relay-mode=polling`
-- (default `cdc`, see PollingRelay + application.yml). This avoids maintaining two
-- divergent schema histories/migration tracks for what is otherwise the same table.
ALTER TABLE outbox_events
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT false;

-- Supports the relay's "unpublished rows ordered by created_at" scan. Partial index
-- (only unpublished rows) keeps it small even though nearly every row is eventually
-- published and this index is a no-op under the CDC-only (default) profile.
CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (created_at)
    WHERE published = false;
