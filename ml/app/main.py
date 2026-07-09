"""FastAPI scoring service (phase-3 T3.2/T3.3).

Endpoints:
  POST /internal/score  -> conversion-probability score + SHAP top_factors
  GET  /health          -> liveness + currently loaded model_version

Model is loaded once at startup (MLflow Production stage, else heuristic fallback — see
model.py). "Internal" = called only by the Java scoring client inside the cluster; no
public exposure (network policy in Phase 5). The service intentionally never raises on a
missing model: it degrades to the heuristic so the app's async recompute never wedges.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .model import ScoringModel
from .schemas import HealthResponse, ScoreRequest, ScoreResponse

logging.basicConfig(level=logging.INFO)

_model = ScoringModel()


@asynccontextmanager
async def lifespan(app: FastAPI):
    _model.load()
    yield


app = FastAPI(title="lead-scoring-service", lifespan=lifespan)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP", model_version=_model.model_version)


@app.post("/internal/score", response_model=ScoreResponse)
def score(req: ScoreRequest) -> ScoreResponse:
    prob, factors = _model.score(
        req.structured_features.model_dump(),
        req.text_features,
    )
    return ScoreResponse(
        score=prob,
        model_version=_model.model_version,
        top_factors=factors,
    )
