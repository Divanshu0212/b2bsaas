# Phase 3 — AI Lead Scoring

**Goal:** Real supervised lead-scoring pipeline. Feature store maintained async, Python FastAPI serving service, offline training with MLflow registry, scores + history + explainability surfaced. ML failure never blocks the app.

**Demo at end:** A lead shows a live conversion-probability score with history and top contributing factors (SHAP). Engagement events (email opens, activity) shift the score over time. Killing the ML service degrades gracefully to last-known score.

**Prerequisites:** Phase 2 CORE passing (events flow; email/activity events exist to build features from).

---

## Tasks

### T3.1 — Feature store table + async maintenance
- `V5__lead_features.sql`: `lead_features` (overview §4) with `feature_version`.
- Scoring consumer subscribes to `email.event.received`, `activity.logged`, `deal.stage.changed`; updates `lead_features` (open/click counts, days-since-last-activity, activity_count_30d, deal_velocity_days, company_size_bucket).
- Feature writes go through the **idempotent-consumer** framework — no silent dual-write (overview change #3).
- **Files:** `V5__lead_features.sql`, `scoring/consumer/FeatureAggregationConsumer.java`.
- **Accept:** an email OPENED event increments `email_open_count` exactly once even on redelivery; `updated_at` moves.

### T3.2 — Python scoring service skeleton
- FastAPI app `lead-scoring-service`, own repo folder + Dockerfile.
- Loads a model from **MLflow registry** (stage = Production) at startup; exposes `POST /internal/score` (contract below) + `/health`.
- sentence-transformers `all-MiniLM-L6-v2` for text; model = XGBoost/LightGBM.
- **Text features: full 384-dim embedding** fed to the tree model (PCA is an optional tuning lever, off by default — overview change #8).
- **Files:** `ml/app/main.py`, `ml/app/model.py`, `ml/app/schemas.py`, `ml/Dockerfile`, `ml/requirements.txt`.
- **Accept:** service starts, loads Production model from MLflow, returns a score + top factors for a sample request.

### T3.3 — Scoring API contract
```json
POST /internal/score
{
  "lead_id": "uuid",
  "text_features": ["notes...", "email snippet..."],
  "structured_features": {
    "email_open_count": 5, "email_click_count": 2,
    "days_since_last_activity": 1, "deal_velocity_days": 14,
    "company_size_bucket": "51-200", "industry": "fintech", "source": "inbound"
  }
}
Response:
{
  "score": 0.812,
  "model_version": "leadscore/Production/v7",
  "top_factors": [
    {"feature": "email_click_count", "impact": 0.18},
    {"feature": "deal_velocity_days", "impact": 0.11}
  ]
}
```
- `model_version` = MLflow model name + stage + version. `top_factors` from SHAP.
- **Accept:** contract validated by pytest + a Java-side WireMock/contract test.

### T3.4 — Java scoring client (resilient)
- `scoring/client` WebClient call to the ML service, wrapped in Resilience4j **circuit breaker + timeout + bulkhead**; fallback = return cached `current_score` / last `lead_scores` row.
- **Async recompute is source of truth** (overview change #9): feature-update consumer triggers recompute (immediate at low volume; batched `@Scheduled` every N min at high volume — config flag, both implemented). Persist a new `lead_scores` row (with `model_version`) and update `leads.current_score`.
- Sync path only on cache-miss / explicit manual refresh.
- **Files:** `scoring/client/ScoringClient.java`, `ScoringService.java`, `ScoreRecomputeConsumer.java`.
- **Accept:** ML service down → app returns last-known score, no error to caller, circuit opens; recompute persists history rows with model_version.

### T3.5 — Score surfacing API + events
- `GET /leads/{id}/score` → latest + history.
- On recompute, emit `lead.score.updated`; notification consumer fires when score crosses a "hot lead" threshold.
- **Files:** `scoring/api/ScoreController.java`, notification wiring.
- **Accept:** score endpoint returns latest+history; crossing threshold produces a notification (idempotently).

### T3.6 — Offline training pipeline
- Batch job (cron container / K8s CronJob — Airflow is STRETCH): pull closed deals (`is_won`/`is_lost`) + their **feature snapshot at close time**, build training set, train classifier, evaluate **AUC-ROC + precision@k** (precision@k matters — reps act on top-N only).
- Log run to MLflow (params, metrics, artifact). If it beats current Production model on held-out set, **register + transition to Production**; serving pod picks it up (restart or MLflow polling reload).
- Feature snapshotting: capture `lead_features` state at deal close so training isn't leaked future data.
- **Files:** `ml/training/train.py`, `ml/training/evaluate.py`, `ml/training/promote.py`, CronJob manifest.
- **Accept:** training run appears in MLflow with metrics; a better model auto-promotes to Production; a worse one does not.

### T3.7 — Explainability surfacing
- SHAP values from `/internal/score` stored/returned so UI can show "why is this lead hot".
- **Accept:** score response's `top_factors` reflect the actual model's SHAP contributions for that input.

---

## Testing requirements (Phase 3)
- **pytest**: feature pipeline correctness, model inference determinism, precision@k/AUC computation, promotion logic (better-model-only).
- **Integration** (Java): circuit-breaker fallback to last-known score, recompute persists history, feature aggregation idempotency, threshold notification.
- **Contract**: score API request/response schema (both sides).
- **Data-leakage test**: training uses close-time feature snapshot, not current state.

## New endpoints (Phase 3)
`GET /leads/{id}/score` · (internal, ML svc) `POST /internal/score`, `GET /health`.

## Tier labels
- CORE: feature store, FastAPI service, resilient client, async recompute, MLflow registry, training job, SHAP.
- STRETCH: Airflow orchestration (K8s CronJob is the CORE path); S3/MinIO artifact store (MLflow local filesystem backend is fine locally).

## Interview talking points
- **Not "call an API and call it AI":** supervised classifier trained on your own won/lost outcomes, embedding as one signal among structured features.
- precision@k over raw accuracy — matches how reps actually consume scores.
- MLflow registry → real A/B + rollback + drift tracking via `model_version` on every score row.
- ML latency never blocks the UI: async recompute is truth, circuit-breaker fallback to last-known.
- Data-leakage avoidance via close-time feature snapshots.

## Risks / gotchas
- **Cold-start**: no historical closed deals → no training data. Seed with synthetic/labeled sample data for the demo and say so.
- Feature snapshot-at-close is easy to get wrong → target leakage inflates offline metrics. Test it.
- Embedding model in memory is heavy — the ML pod needs bigger resource limits (Phase 5).
- Keep `model_version` string consistent between MLflow, the score row, and the API response, or A/B analysis breaks.
