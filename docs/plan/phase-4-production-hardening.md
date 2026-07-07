# Phase 4 — Production Hardening

**Goal:** Make it observable, resilient, and load-proven. Full metrics/tracing/logging, DLQ management, rate limiting, resilience patterns everywhere, and published Gatling numbers.

**Demo at end:** Grafana dashboards show request latency, consumer lag, DLQ depth, ML latency, circuit-breaker state. A single deal-stage-change is followable end-to-end in a distributed trace across the Kafka boundary. README shows p99 latency + consumer lag under load from Gatling.

**Prerequisites:** Phases 1–3 CORE passing.

---

## Tasks

### T4.1 — Metrics (Micrometer → Prometheus + Grafana)
- Micrometer registry → Prometheus scrape endpoint. Add compose/K8s Prometheus + Grafana.
- Custom metrics: request latency/error per endpoint, **Kafka consumer lag**, **DLQ depth**, ML scoring latency, circuit-breaker state transitions, outbox/CDC relay lag, DB pool saturation.
- Grafana dashboards (as-code JSON) + Alertmanager rules (high DLQ depth, lag spike, CB open, error-rate SLO burn).
- **Files:** `observability/prometheus.yml`, `observability/grafana/*.json`, `observability/alerts.yml`.
- **Accept:** dashboards render live metrics; an induced DLQ message raises DLQ-depth panel + fires an alert.

### T4.2 — Distributed tracing (OpenTelemetry)
- OTel SDK/agent; export to Tempo/Jaeger. Instrument HTTP, DB, Kafka.
- **Propagate `trace_id` across the Kafka boundary** via message headers (finally fills the `outbox_events.trace_id` placeholder from Phase 2). One trace spans: request → outbox → CDC → consumer → DB write → notification.
- **Files:** OTel config, header propagation in producer/consumer, trace_id capture in `OutboxRecorder`.
- **Accept:** a `PATCH /deals/{id}/stage` produces one connected trace across producer and all consumers.

### T4.3 — Structured logging → aggregation
- JSON logs with `org_id`/`trace_id`/`user_id` in MDC (CORE: stdout).
- **STRETCH:** ship to Loki/ELK; log-to-trace correlation via `trace_id`.
- **Accept:** logs are JSON, carry trace_id matching the trace in T4.2; (STRETCH) searchable in Loki.

### T4.4 — Resilience pass (complete §6.3)
- Circuit breakers: ML calls (Phase 3) **and** outbound email provider (real SendGrid/SES client now, behind CB + retry + timeout).
- Bulkhead: separate thread pools for ML vs DB calls.
- Idempotency keys on client-retryable writes (email send) — dedupe store or `Idempotency-Key` header + Redis.
- Timeouts on every outbound HTTP call; no unbounded waits anywhere.
- **Files:** `common/resilience/*`, email provider client, idempotency filter.
- **Accept:** slow ML service can't exhaust request threads (bulkhead proven under load); duplicate email-send with same idempotency key sends once.

### T4.5 — DLQ management
- DLQ inspection endpoint/admin (list, reason, count) + replay tooling (re-publish to source topic after fix).
- Alert on DLQ depth (T4.1).
- **Accept:** a failed message can be inspected and replayed successfully after the cause is fixed.

### T4.6 — Rate limiting (broaden)
- Bucket4j + Redis per-tenant on public-ish endpoints (tracking pixel, webhook) — verify under burst; add sensible per-tenant API quotas.
- **Accept:** per-tenant limit enforced; one tenant's burst doesn't throttle another.

### T4.7 — GDPR / retention (overview change #12)
- Tenant hard-delete job: remove all `org_id` rows across every table; detach/drop archived partitions for that tenant's data.
- Documented retention windows for `activities` / `email_events`; partition-drop retention job.
- **Accept:** deleting a tenant leaves zero rows for that `org_id` anywhere; retention job drops partitions past the window.

### T4.8 — Testcontainers integration suite consolidation
- Full suite: Postgres + Kafka + Redis + (schema registry) real infra in CI. No mocks for infra-boundary tests.
- **Accept:** CI runs the suite green; coverage gate met.

### T4.9 — Load testing (Gatling)
- Scenarios: Kanban drag bursts (concurrent stage PATCH), lead list pagination, Kafka consumer throughput, email-tracking pixel storm.
- Derive HikariCP pool size + K8s resource requests from results (overview §6.4).
- **Publish p99 latency + consumer lag under load in README** — concrete numbers beat "it's scalable."
- **Files:** `loadtest/*.scala`, README results section.
- **Accept:** Gatling run produces a report; README has real p99 + lag figures; pool/resource sizing justified by these numbers.

---

## Testing requirements (Phase 4)
- Integration: bulkhead isolation under induced ML slowness, idempotency-key dedupe, DLQ replay, tenant hard-delete completeness, rate-limit fairness.
- Load: Gatling scenarios above.
- Chaos-lite (optional, sets up stretch): kill a consumer mid-process, verify no message loss (covered by outbox + idempotency).

## New endpoints (Phase 4)
Admin: DLQ list/replay · tenant delete (ADMIN-only) · Prometheus `/actuator/prometheus`.

## Tier labels
- CORE: Prometheus/Grafana, OTel tracing, JSON logs (stdout), full resilience, DLQ mgmt, rate limiting, GDPR delete, Testcontainers suite, Gatling.
- STRETCH: Loki/ELK aggregation, chaos testing.

## Interview talking points
- Trace a single request across the Kafka boundary — the thing that actually impresses in a systems round.
- Bulkhead proven: a slow dependency can't starve request threads (with the load test to back it).
- DLQ + replay = no silent message loss; you can show the failure→fix→replay loop.
- Real published latency/lag numbers, and resource sizing derived from them — not guessed.
- GDPR hard-delete: you thought about the legal side of multi-tenant PII.

## Risks / gotchas
- trace_id must be captured at outbox-write time and re-hydrated in the consumer, or the trace breaks exactly at the async boundary you're trying to show off.
- Gatling against a laptop gives laptop numbers — state the environment honestly in the README.
- Idempotency-key store needs TTL/cleanup or it grows unbounded.
