-- T3.4/T3.5: full score history (docs/plan/00-overview.md §4, phase-3 plan T3.4).
-- Every async recompute persists one row here (with the MLflow model_version that
-- produced it) and updates leads.current_score to the latest. "Async recompute is the
-- source of truth" (overview change #9); this table is the append-only audit/history
-- behind GET /leads/{id}/score and the A/B/rollback analysis surface (model_version on
-- every row — keep that string consistent with MLflow + the /internal/score response,
-- per the phase-3 "Risks/gotchas").
CREATE TABLE lead_scores (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    lead_id       UUID NOT NULL REFERENCES leads(id),
    score         NUMERIC(5,4) NOT NULL,
    model_version TEXT,
    top_factors   JSONB,          -- SHAP top contributors captured at score time (T3.7)
    scored_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Latest-and-history read path: GET /leads/{id}/score orders by scored_at DESC within
-- (org_id, lead_id).
CREATE INDEX idx_lead_scores_org_lead_scored
    ON lead_scores (org_id, lead_id, scored_at DESC);
