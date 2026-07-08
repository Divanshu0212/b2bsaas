# SalesPipe — Implementation Plan

Buildable, production-correct plan derived from the SDD (`../../b2b-saas-crm-design-document.md`), with correctness fixes and industry-grade upgrades applied.

## Read in order
1. [00-overview.md](00-overview.md) — architecture, corrected schema, decision log, component tier map, cross-cutting specs. **Start here.**
2. [phase-1-core-monolith.md](phase-1-core-monolith.md) — auth, multi-tenancy, CRM core, Kanban, Docker, K8s.
3. [phase-2-event-backbone.md](phase-2-event-backbone.md) — outbox → Debezium CDC → Kafka, idempotent consumers, activity timeline, email tracking.
4. [phase-3-ai-scoring.md](phase-3-ai-scoring.md) — feature store, FastAPI scoring service, MLflow, training pipeline, SHAP.
5. [phase-4-production-hardening.md](phase-4-production-hardening.md) — observability, tracing, resilience, DLQ, GDPR, Gatling.
6. [phase-5-platform.md](phase-5-platform.md) — Helm, KEDA, Argo CD GitOps, External Secrets.
7. [phase-6-frontend.md](phase-6-frontend.md) — Next.js web client: Kanban, lead detail with live AI score + SHAP, notifications, reporting. Not in the original SDD — plan-only addition.

## How to use
- Each phase doc is independently executable — one phase ≈ one implementation session.
- Do not start a phase before the prior phase's **CORE** tasks pass their acceptance tests.
- Component tiers (CORE vs STRETCH) and local fallbacks are in overview §2.

## Key improvements over the SDD
See overview §1 for the full change log. Headlines: real idempotency store, Debezium CDC outbox relay, HMAC-signed email tracking, refresh-token reuse detection, MLflow registry, schema registry, async-recompute scoring, partitioning, GDPR delete.
