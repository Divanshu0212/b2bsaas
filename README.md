# SalesPipe — B2B SaaS Sales CRM

A multi-tenant B2B Sales CRM built as a **modular monolith** on Kubernetes, demonstrating production-grade backend engineering: event-driven architecture, distributed-systems primitives, and a real (not hand-wavy) ML lead-scoring pipeline.

Sales teams move **Leads → Deals** through a **Kanban pipeline**, log **activities** (calls, emails, notes, meetings), track **email opens/clicks**, and get **AI-ranked lead scores** that predict conversion likelihood. State changes flow as domain events, consumed asynchronously to update timelines, fire notifications, and recompute scores — without blocking the request thread.

> **Status:** Implementation plan complete. Code is built phase by phase from the plan in [`docs/plan/`](docs/plan/).

---

## Why this project

A CRUD CRM is forgettable. This one is built to survive a system-design interview:

| Area | What it demonstrates |
|---|---|
| Domain modeling | Multi-tenant B2B domain with real invariants (pipeline stages, ownership, audit trail) |
| Distributed systems | Transactional outbox, CDC, idempotent consumers, at-least-once delivery, eventual consistency |
| Async architecture | Kafka event bus decoupling the write path from side effects |
| ML in production | Feature engineering (embeddings + structured signals) → supervised classifier, MLflow registry, offline retraining |
| Production concerns | Observability, resilience (circuit breakers, retries, DLQs), rate limiting |
| Platform / DevOps | Docker, Kubernetes, Helm, HPA + KEDA, GitOps CI/CD |
| Testing | Testcontainers integration tests, contract tests, load tests |

---

## Architecture at a glance

- **Modular monolith** (Spring Modulith) — module boundaries enforced in CI; a cross-module violation fails the build. Designed for future service extraction without paying that tax now.
- **Transactional outbox → Debezium CDC → Kafka** — no dual-write bug; the app never produces to Kafka directly.
- **Idempotent consumers** — at-least-once delivery made safe via a `processed_events` dedupe store.
- **AI lead scoring** — Python FastAPI service, Sentence-BERT embeddings + gradient-boosted classifier trained on your own won/lost outcomes, served with a circuit breaker and last-known-score fallback.
- **Multi-tenancy** — shared schema, `org_id` on every table, enforced by a Hibernate filter (never trusted from the request body).

Full architecture, corrected schema, and the decision log live in [`docs/plan/00-overview.md`](docs/plan/00-overview.md).

---

## Tech stack

| Layer | Choice |
|---|---|
| Language / framework | Java 21 (virtual threads), Spring Boot 3.x, Spring Modulith |
| Persistence | PostgreSQL 16, Spring Data JPA / Hibernate, Flyway |
| Caching / rate limit | Redis, Bucket4j |
| Event bus | Apache Kafka + Schema Registry, Debezium (CDC outbox relay) |
| Security | Spring Security 6, JWT (access + rotating refresh with reuse detection), RBAC |
| Resilience | Resilience4j (circuit breaker, retry, bulkhead, rate limiter) |
| ML service | Python, FastAPI, sentence-transformers, XGBoost/LightGBM, MLflow, SHAP |
| Observability | Micrometer → Prometheus + Grafana, OpenTelemetry, structured JSON logs |
| Platform | Docker (multi-stage), Kubernetes + Helm, HPA + KEDA, Argo CD (GitOps) |
| Testing | JUnit 5, Mockito, Testcontainers, RestAssured, Gatling, pytest |

---

## Build roadmap

Built in phases — each is independently demoable. See [`docs/plan/`](docs/plan/) for the full task breakdown per phase.

1. **[Phase 1 — Core CRUD monolith](docs/plan/phase-1-core-monolith.md)** — auth, multi-tenancy, CRM core, Kanban deals, Docker, K8s.
2. **[Phase 2 — Event backbone](docs/plan/phase-2-event-backbone.md)** — outbox → CDC → Kafka, idempotent consumers, activity timeline, email tracking.
3. **[Phase 3 — AI lead scoring](docs/plan/phase-3-ai-scoring.md)** — feature store, FastAPI scoring service, MLflow, training pipeline, SHAP.
4. **[Phase 4 — Production hardening](docs/plan/phase-4-production-hardening.md)** — observability, tracing, resilience, DLQ, GDPR, Gatling.
5. **[Phase 5 — Platform polish](docs/plan/phase-5-platform.md)** — Helm, KEDA, Argo CD GitOps, External Secrets.

Component tiers (CORE vs STRETCH) and local fallbacks are documented in the overview.

---

## Documentation

- [`docs/plan/`](docs/plan/) — implementation plan (start with the [overview](docs/plan/00-overview.md)).
- [`b2b-saas-crm-design-document.md`](b2b-saas-crm-design-document.md) — original software design document (SDD).

---

## License

MIT
