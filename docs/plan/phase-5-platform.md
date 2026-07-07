# Phase 5 — Platform Polish

**Goal:** Production-grade delivery: Helm chart, KEDA autoscaling on Kafka lag, GitOps CI/CD with Argo CD, secrets via External Secrets Operator. One-command, zero-downtime deploys.

**Demo at end:** Push to main → CI builds/tests → image published → Argo CD reconciles → rolling zero-downtime deploy. Consumer pods scale up under Kafka lag (KEDA), back down when drained. Secrets pulled from a vault, not committed.

**Prerequisites:** Phases 1–4 CORE passing.

---

## Tasks

### T5.1 — Helm chart
- Chart for the Spring Boot app + separate chart/values for the ML service (heavier CPU/mem limits — embedding model in memory).
- Templated: Deployment, Service, ConfigMap, Secret refs, HPA, probes, PDB, Ingress.
- Per-env values (`values-staging.yaml`, `values-prod.yaml`).
- **Files:** `charts/salespipe/*`, `charts/lead-scoring/*`.
- **Accept:** `helm install` brings up the full stack on a cluster; `helm upgrade` rolls with zero dropped requests (verify with a small load during upgrade).

### T5.2 — K8s production surface
- **RollingUpdate** with tuned `maxSurge`/`maxUnavailable` for zero downtime.
- **HPA** (CPU + custom metric) on the app.
- **PodDisruptionBudget** so node drains don't take all replicas at once.
- **Probes**: liveness/readiness/startup (readiness fails while DB/Kafka unreachable → pulled from Service before traffic).
- **Ingress** (nginx) + TLS via **cert-manager**.
- **Resource requests/limits** set from Phase 4 load-test numbers (not defaults).
- **Accept:** rolling deploy = zero 5xx during rollout; draining a node keeps ≥ minAvailable replicas; TLS terminates via cert-manager.

### T5.3 — KEDA autoscaling on Kafka lag (STRETCH, high-value)
- **KEDA ScaledObject** on the consumer workload scaling by **consumer-group lag** (not CPU).
- Fallback: HPA on CPU only if KEDA unavailable.
- **Files:** `charts/salespipe/templates/keda-scaledobject.yaml`.
- **Accept:** injecting lag (pause consumers, flood a topic) scales consumer pods up; draining scales them back to min. Show the lag→replica correlation in Grafana.

### T5.4 — GitOps CI/CD (Argo CD) (STRETCH)
- GitHub Actions: build + unit/pytest → Testcontainers integration → SonarQube/SonarCloud quality gate → multi-stage Docker build (distroless/JRE-slim) → push to GHCR/ECR tagged with git SHA → bump image tag in the GitOps repo.
- **Argo CD** watches the GitOps repo, auto-syncs staging, manual promote to prod (declarative desired state, not `kubectl apply` from CI).
- Fallback (CORE path if no Argo): `helm upgrade` from the pipeline.
- **Files:** `.github/workflows/*.yml`, `gitops/` repo layout, Argo `Application` manifests.
- **Accept:** merge to main → new SHA image → Argo syncs staging automatically; prod requires manual promote.

### T5.5 — Secrets management (STRETCH)
- **External Secrets Operator** pulling from Vault / AWS Secrets Manager; K8s Secrets synced, never committed.
- Fallback (CORE): plain K8s Secrets + documented gap.
- **Accept:** app reads DB/Kafka/JWT secrets from ESO-synced secrets; no secret material in git or ConfigMaps.

### T5.6 — Managed-service story
- Document: local = Postgres/Redis/Kafka in-cluster (StatefulSet/PVC); "real" = RDS/ElastiCache/MSK. Be explicit which you ran and why.
- **Accept:** README clearly states the local vs managed split and the migration path.

---

## Testing / verification (Phase 5)
- Zero-downtime deploy verified under live load (small Gatling run during `helm upgrade`).
- KEDA scale-up/down demonstrated and screenshotted from Grafana.
- Full CI pipeline green end-to-end on a real push.
- Secret-scanning check in CI (no committed secrets).

## Tier labels
- CORE: Helm chart, RollingUpdate/HPA/PDB/probes/Ingress/cert-manager, resource sizing, managed-service doc.
- STRETCH: KEDA (HPA fallback), Argo CD (helm-upgrade fallback), ESO+Vault (plain Secrets fallback), Airflow (from Phase 3).

## Interview talking points
- **GitOps** (declarative desired state, Argo reconciles) vs "kubectl apply from a pipeline" — a genuinely more senior distinction.
- **KEDA on consumer lag** ties K8s scaling directly to the event system — non-generic, memorable.
- Zero-downtime rollout with PDB + tuned surge + readiness gating — the operational skills interviewers actually test.
- Resource limits derived from load tests, secrets externalized — you know where the production gaps are even where you used the local fallback.

## Risks / gotchas
- KEDA scaler needs metrics access to Kafka lag — wire the trigger auth correctly or it silently won't scale.
- Argo auto-sync + a bad image tag = auto-deploy of a broken build; keep the quality gate + staging-before-prod promote.
- cert-manager DNS/HTTP01 challenge fails silently behind some local ingresses; document the local TLS shortcut if used.
- Don't let `values-prod.yaml` inherit demo resource limits — the ML pod especially needs real memory for the embedding model.

---

## Stretch goals (post-Phase-5, from SDD §19)
- Elasticsearch full-text lead/note search (Postgres `tsvector` is the CORE fallback).
- WebSocket/SSE live Kanban across users (polling fallback).
- SHAP explainability surfaced richly in UI.
- Saga handling if a second deployable (e.g. billing) is introduced.
- Chaos testing: kill a pod mid-Kafka-consume, verify no message loss (outbox + idempotency should hold).
