# Phase 4 — Production Hardening (Design)

**Date:** 2026-07-09
**Branch:** `phase-4-production-hardening` (commit per task T4.1…T4.9; merge to `main` at end)
**Tier:** CORE + STRETCH (Loki aggregation + chaos-lite included)

## Goal

Make SalesPipe observable, resilient, and load-proven. Full metrics/tracing/logging,
DLQ management with replay, broadened rate limiting, complete resilience patterns,
GDPR hard-delete, consolidated Testcontainers suite, and published Gatling numbers.

**End state demo:** Grafana dashboards show request latency, consumer lag, DLQ depth,
ML latency, circuit-breaker state. A single deal-stage-change is followable end-to-end
in one distributed trace across the Kafka boundary. README shows real p99 latency +
consumer lag under load.

## Prerequisites

Phases 1–3 CORE passing (merged: `668086e`). Existing infra to build on:
- resilience4j (`resilience4j-spring-boot3:2.4.0`) — partial CBs on ML/consumers
- bucket4j + Redis (`bucket4j_jdk17-*:8.14.0`) — rate limit on tracking pixel/webhook
- actuator, logstash-logback-encoder (JSON logs), Testcontainers (pg/kafka/redis)
- `outbox_events.trace_id` column exists as a placeholder (V2 migration) — filled here

---

## Decisions

| Topic | Choice | Rationale |
|-------|--------|-----------|
| Scope | CORE + STRETCH | Loki log aggregation + chaos-lite included |
| Trace backend | Grafana Tempo | Single Grafana UI for metrics+traces; OTLP export |
| Email provider | Real SendGrid SDK | Config-gated; no-op sender when key absent so tests/demo run without secrets |

---

## Section 1 — Observability stack (T4.1, T4.2, T4.3)

### Containers (compose + k8s)
Prometheus, Grafana, Tempo, Loki (STRETCH), OTLP collector path. Add to
`docker-compose.yml` and `k8s/`.

### T4.1 — Metrics (Micrometer → Prometheus + Grafana)
- Add `micrometer-registry-prometheus`. `/actuator/prometheus` exposed (actuator present).
- Custom meters:
  - Kafka consumer lag (`MeterBinder` reading consumer group lag)
  - DLQ depth (gauge on `DlqPublisher` / DLQ topic)
  - ML scoring latency (timer in `ScoringClient`)
  - Circuit-breaker state transitions (resilience4j micrometer binder)
  - Outbox/CDC relay lag
  - Hikari DB pool saturation (auto-bound)
- Dashboards as-code JSON: `observability/grafana/*.json`.
- Alertmanager rules: `observability/alerts.yml` — high DLQ depth, lag spike, CB open,
  error-rate SLO burn.
- **Accept:** dashboards render live metrics; induced DLQ message raises DLQ-depth panel
  and fires an alert.

### T4.2 — Distributed tracing (OpenTelemetry → Tempo)
- OTel Spring Boot starter → OTLP export to Tempo. Auto-instrument HTTP, DB, Kafka.
- **Kafka boundary propagation:**
  - Capture `trace_id` at outbox-write time in `OutboxRecorder` (fills existing
    `outbox_events.trace_id` column).
  - Propagate trace context via Kafka message headers (producer side).
  - Rehydrate span context in consumers so the trace continues across the async boundary.
- One trace spans: `PATCH /deals/{id}/stage` → outbox → CDC → consumer → DB write →
  notification.
- **Files:** OTel config, header propagation in producer/consumer, trace_id capture in
  `OutboxRecorder`.
- **Accept:** a `PATCH /deals/{id}/stage` produces one connected trace across producer and
  all consumers.
- **Gotcha:** trace_id must be captured at outbox-write and rehydrated in consumer, else the
  trace breaks exactly at the async boundary being demoed.

### T4.3 — Structured logging (STRETCH: → Loki)
- JSON logs (logstash encoder present) with `org_id`/`trace_id`/`user_id` in MDC; stdout (CORE).
- STRETCH: Promtail ships stdout → Loki; trace↔log correlation by `trace_id` in Grafana.
- **Accept:** logs are JSON, carry trace_id matching T4.2 trace; (STRETCH) searchable in Loki.

---

## Section 2 — Resilience completion (T4.4)

- **Real SendGrid SDK** outbound email client behind CB + retry + timeout.
  - New outbound path: hot-lead / deal-stage notification consumers → `EmailProvider`
    interface → SendGrid client.
  - Config-gated: no API key → no-op logging sender, so tests + cold-start demo run without
    secrets. API key from env, never committed.
- **Bulkhead:** separate resilience4j thread pools for ML calls vs DB calls. Slow ML service
  cannot starve request threads.
- **Idempotency keys** on client-retryable writes (email send): Redis dedupe store with TTL,
  or `Idempotency-Key` header. Same key sends once.
- **Timeouts** audited on every outbound HTTP call (`ScoringClient`, SendGrid client) — no
  unbounded waits anywhere.
- **Files:** `common/resilience/*`, `notification/infra/email/*`, idempotency filter/store.
- **Accept:** slow ML service can't exhaust request threads (bulkhead proven under load);
  duplicate email-send with same idempotency key sends once.
- **Gotcha:** idempotency-key store needs TTL/cleanup or grows unbounded.

---

## Section 3 — DLQ, rate limiting, GDPR (T4.5, T4.6, T4.7)

### T4.5 — DLQ management
- ADMIN endpoints: list DLQ messages (topic, reason, count), replay (re-publish to source
  topic after fix). Backed by existing `DlqPublisher` infra.
- Alert on DLQ depth wired in T4.1.
- **Accept:** a failed message can be inspected and replayed successfully after cause fixed.

### T4.6 — Rate limiting (broaden)
- Extend existing bucket4j + Redis per-tenant limiting from tracking pixel/webhook to
  per-tenant API quotas on public-ish endpoints. Verify under burst.
- **Accept:** per-tenant limit enforced; one tenant's burst doesn't throttle another.

### T4.7 — GDPR / retention
- ADMIN-only tenant hard-delete job: remove all `org_id` rows across every table;
  detach/drop archived partitions for that tenant's data.
- Documented retention windows for `activities` / `email_events`; partition-drop retention job.
- **Accept:** deleting a tenant leaves zero rows for that `org_id` anywhere; retention job
  drops partitions past the window.

---

## Section 4 — Testing & load (T4.8, T4.9) + STRETCH chaos

### T4.8 — Testcontainers integration suite consolidation
- Full suite: Postgres + Kafka + Redis (+ schema registry) real infra in CI. No mocks for
  infra-boundary tests.
- Integration tests added this phase: bulkhead isolation under induced ML slowness,
  idempotency-key dedupe, DLQ replay, tenant hard-delete completeness, rate-limit fairness.
- **Accept:** CI runs the suite green; coverage gate met.

### T4.9 — Load testing (Gatling)
- Scenarios: Kanban drag bursts (concurrent stage PATCH), lead list pagination, Kafka
  consumer throughput, email-tracking pixel storm.
- Derive HikariCP pool size + K8s resource requests from results.
- **Publish p99 latency + consumer lag under load in README** — real numbers, honest about
  the laptop environment.
- **Files:** `loadtest/*.scala`, README results section.
- **Accept:** Gatling run produces a report; README has real p99 + lag figures; pool/resource
  sizing justified by these numbers.

### STRETCH — chaos-lite
- Kill a consumer mid-process, verify no message loss (covered by outbox + idempotency).

---

## New endpoints (Phase 4)
Admin: DLQ list/replay · tenant delete (ADMIN-only) · Prometheus `/actuator/prometheus`.

## Commit cadence
One commit per task T4.1…T4.9 on `phase-4-production-hardening`. Merge to `main` at end.
Commit identity: `divanshu0212 <divanshu0212@gmail.com>`, no Claude co-author trailer.
