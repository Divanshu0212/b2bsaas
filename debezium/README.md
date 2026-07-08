# Debezium outbox relay (T2.3)

`outbox-connector.json` is a Kafka Connect connector config (POST body for the Connect
REST API) using `io.debezium.connector.postgresql.PostgresConnector` with the
`io.debezium.transforms.outbox.EventRouter` SMT applied to `public.outbox_events`.

## Routing convention — event_type, not aggregate_type

Debezium's own outbox-router docs usually route by `aggregate_type` (coarse: one topic
per aggregate). SalesPipe's topic list (`docs/plan/00-overview.md` §5) is finer-grained
than that — `outbox_events.event_type` values (`deal.stage.changed`, `lead.created`,
`email.event.received`, `lead.score.updated`, `activity.logged`) are already exactly the
Kafka topic names. `aggregate_type` cannot be the routing field here: `aggregate_type =
lead` alone fans out to three different topics (`lead.created`, `lead.score.updated`,
`email.event.received`), so a per-aggregate-type route would collapse them incorrectly.

So the connector is configured with:
```
transforms.outbox.route.by.field = event_type
transforms.outbox.route.topic.replacement = ${routedByValue}
```
i.e. the row's own `event_type` column value becomes the destination topic name
directly (identity template), matching `com.salespipe.eventing.Topics`.

## Key routing — the critical setting

`transforms.outbox.table.field.event.key = aggregate_id`. This becomes the outgoing
Kafka message **key**, giving per-aggregate ordering (all events for one deal/lead land
on one partition, in order — see plan's "Risks/gotchas": wrong key routing breaks
ordering). Debezium's own default (the source table's PK, `id` — one per *event*, not
per *aggregate*) would silently break ordering; do not revert to it.

## Value shape gotcha for future consumer work (T2.4+)

The outbox-event-router SMT emits the outbox row's raw `payload` JSONB column as the
Kafka message **value** (`JsonConverter`, `schemas.enable=false`) — it does **not**
reconstruct the full canonical `EventEnvelope` (`eventId`/`eventType`/`schemaVersion`/
`orgId`/`aggregateType`/`aggregateId`/`occurredAt`/`traceId`) as the top-level value
shape the way `EventPublisher`/`PollingRelay` do for the direct-produce and
polling-fallback paths.

Envelope fields other than `payload` are carried instead as:
- Kafka message **key** = `aggregate_id`
- Kafka message **timestamp** = `created_at`
- Kafka message **headers**: `aggregateType`, `orgId`, `traceId` (via
  `table.fields.additional.placement`)
- `event_type` is implicit in the topic name itself once routed

**Before building T2.4's idempotent consumer**, decide whether consumers reconstruct
the envelope from key + headers + value, or whether this connector config needs a
follow-up transform (e.g. chaining a second SMT to rebuild an envelope-shaped value)
so CDC-relayed messages deserialize identically to `EventEnvelope`-shaped messages
produced directly (`EventingSmokeIT`'s path) or via `PollingRelay`. This asymmetry is
real and unresolved — flagging it now rather than silently assuming T2.4 will "just
work" against both paths uniformly.

## Manual verification runbook (documented, not automated)

A full Debezium Connect + Kafka Connect Testcontainers IT was judged not worth the
setup cost for this task (no lightweight `testcontainers-debezium` module in this
stack's dependency set; standing up real Kafka Connect + Debezium plugin containers in
an IT is heavy relative to the payoff, especially with the polling-relay fallback
already fully IT-tested as the safety net the plan calls for). Validate manually:

```bash
docker compose up -d postgres kafka schema-registry debezium

# wait for Kafka Connect's REST API
curl -sf http://localhost:8083/connectors

# register the connector
curl -s -X POST -H 'Content-Type: application/json' \
  --data @debezium/outbox-connector.json \
  http://localhost:8083/connectors

# confirm it's running
curl -s http://localhost:8083/connectors/salespipe-outbox-connector/status | jq

# insert a row directly (or trigger any outbox-writing code path once wired in T2.7)
docker compose exec postgres psql -U salespipe -d salespipe -c \
  "INSERT INTO outbox_events (id, org_id, aggregate_type, aggregate_id, event_type, payload, created_at) \
   VALUES (gen_random_uuid(), (SELECT id FROM organizations LIMIT 1), 'deal', 'deal-123', \
   'deal.stage.changed', '{\"dealId\":\"deal-123\",\"toStageId\":\"stage-1\"}'::jsonb, now());"

# consume the routed topic and check the key
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic deal.stage.changed \
  --from-beginning \
  --property print.key=true
```
Expect: one message on `deal.stage.changed`, key = `deal-123`, no polling involved.
