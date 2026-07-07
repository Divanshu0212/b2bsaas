# Phase 2 — Event Backbone

**Goal:** Make state changes event-driven. Transactional outbox → Debezium CDC → Kafka → idempotent in-process consumers. Activity timeline populated async. Email tracking (open/click) with forgery-proof tokens.

**Demo at end:** Drag a deal → within moments its activity timeline shows a STAGE_CHANGE entry (written by a consumer, not the request thread). Send a tracked email; opening it records an OPENED event; a spoofed pixel request is rejected.

**Prerequisites:** Phase 1 CORE passing.

---

## Tasks

### T2.1 — Kafka + Schema Registry infra
- Add Kafka + Schema Registry to `docker-compose.yml`.
- `eventing` module: shared producer/consumer config, canonical event envelope (overview §5), JSON Schema per topic registered at startup.
- **Files:** `eventing/schema/*.json`, `eventing/producer/*`, `eventing/consumer/*`, compose updates.
- **Accept:** app connects to Kafka; schemas registered; a smoke test round-trips one event through a topic.

### T2.2 — Transactional outbox write path
- `V2__outbox.sql`: create `outbox_events` (no `published` column — CDC path) and `processed_events`.
- Domain services insert an `outbox_events` row **in the same transaction** as the business write. Add a small `OutboxRecorder` helper so modules don't hand-roll it.
- `trace_id` captured into the row (populated properly in Phase 4; placeholder MDC value acceptable now).
- **Files:** `V2__outbox.sql`, `eventing/outbox/OutboxRecorder.java`, `OutboxEvent.java`.
- **Accept:** integration test: a stage change commits both the deal update and exactly one outbox row atomically; rollback drops both.

### T2.3 — Debezium CDC relay (CORE) + polling fallback
- Debezium Connect in compose; configure the **outbox-event-router** SMT on `outbox_events` → routes to topics by `aggregate_type`/`event_type`, key = `aggregate_id`.
- **Fallback**: a `@Scheduled` polling relay (reads unpublished rows, publishes, marks `published`) behind a config flag, for environments without Debezium. Requires the `published` column variant — ship both migrations guarded by profile, documented clearly.
- **Files:** `debezium/outbox-connector.json`, `eventing/outbox/PollingRelay.java` (fallback), profile config.
- **Accept:** with Debezium, an outbox insert appears on the correct Kafka topic with key=`aggregate_id`, no polling. Toggling to fallback profile still delivers.

### T2.4 — Idempotent consumer framework
- Base consumer that, in one transaction: checks/inserts `processed_events (consumer_group, event_id)` → on unique-violation, skip (already processed); else run handler + business write.
- Resilience4j retry (exp backoff) on handler failure; after N attempts publish to `<topic>.DLQ` with failure reason.
- Java 21 virtual threads for consumer executors.
- **Files:** `eventing/consumer/IdempotentConsumer.java`, `DlqPublisher.java`.
- **Accept:** delivering the same event twice runs the handler once; a poison message lands in DLQ with reason after retries, not silently dropped.

### T2.5 — Activity module: timeline consumers
- Create `activities` table: `V3__activities.sql`, **partitioned monthly** by `created_at`, GIN index on `payload`, composite `(org_id, entity_type, entity_id, created_at DESC)`. Partition provisioning (pg_partman or Flyway-managed `create_next_partition`).
- Consumers on `deal.stage.changed`, `lead.created`, `activity.logged` append timeline rows (append-only, never update).
- `GET /leads/{id}/timeline` merges the feed.
- **Files:** `V3__activities.sql`, `activity/consumer/*`, `activity/api/TimelineController.java`.
- **Accept:** stage drag → timeline shows STAGE_CHANGE written by consumer; timeline endpoint returns merged, paginated, tenant-scoped feed.

### T2.6 — Email tracking (forgery-proof)
- `V4__email_tracking.sql`: `emails` (with `tracking_sig`), `email_events` (partitioned monthly by `occurred_at`).
- Outbound send stub (provider integration is Phase 4 resilience concern): generates `tracking_id` + `tracking_sig = HMAC(tracking_id, org secret)`.
- `GET /emails/{trackingId}/open?sig=` → validate HMAC; on success return 1×1 `image/gif` and **async** emit `email.event.received` (OPENED); invalid sig → 204/404 with no DB write.
- `GET /emails/{trackingId}/click?sig=&url=` → validate, 302 redirect, async CLICKED event.
- Webhook ingestion endpoint for provider bounce/delivery events (SendGrid/SES shape), also emits `email.event.received`.
- Bucket4j per-tenant rate limit on these endpoints.
- **Files:** `V4__email_tracking.sql`, `emailtracking/api/TrackingController.java`, `WebhookController.java`, `TrackingSigner.java`.
- **Accept:** valid pixel request logs OPENED via the event path; tampered `sig` rejected pre-write; rate limit trips under burst.

### T2.7 — Wire producers into Phase 1 write paths
- Pipeline stage change, lead creation now record outbox events (`deal.stage.changed`, `lead.created`). Notification consumer stub for `deal.stage.changed` (full notifications Phase 4-ish; minimal in-app `notifications` row now).
- **Accept:** end-to-end: `PATCH /deals/{id}/stage` → outbox → CDC → Kafka → activity + notification consumers both fire idempotently.

---

## Testing requirements (Phase 2)
- **Integration** (Testcontainers Kafka + Postgres): outbox atomicity, CDC delivery (or polling fallback), idempotent double-delivery, DLQ routing on poison message, timeline population, HMAC open validation.
- **Contract**: schema-registry backward-compat check in CI (new schema must be compatible).
- **Unit**: HMAC signer, DLQ decision logic, partition-name computation.

## New endpoints (Phase 2)
`GET /leads/{id}/timeline` · `GET /emails/{trackingId}/open` · `GET /emails/{trackingId}/click` · `POST /webhooks/email` · (internal) email send.

## Tier labels
- CORE: Kafka, Schema Registry, outbox, idempotent consumers, activity timeline, email tracking, DLQs.
- STRETCH: none new; Debezium is CORE with the polling relay as its documented fallback (still ship-able without Debezium).

## Interview talking points
- **The** distributed-systems story: dual-write avoided via transactional outbox + CDC; at-least-once made safe by idempotent consumers keyed on `event_id`.
- Per-aggregate ordering via partition key; replay-ability (re-emit history to re-score leads — sets up Phase 3).
- Schema Registry enforces backward-compatible event evolution.
- HMAC-signed tracking tokens defeat open/click forgery + id enumeration.

## Risks / gotchas
- Debezium outbox-router SMT config is fiddly; get key routing (`aggregate_id`) right or ordering breaks. Have the polling fallback working first as a safety net.
- Partition provisioning must run ahead of time — a missing future partition = insert failure. Automate it.
- Consumer dedupe insert must share the handler's transaction, or a crash between dedupe-commit and business-commit reintroduces the dual-write bug you just removed.
- Don't let the notification consumer double-notify on redelivery — it's covered by `processed_events`, but test it explicitly.
