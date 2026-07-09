"""Model loading + inference for the scoring service (phase-3 T3.2/T3.7).

Load order at startup:
  1. Try the MLflow registry, stage=Production (the real path; overview change #7).
  2. Fall back to a deterministic heuristic model if MLflow / the registered model is
     unavailable — so the service still starts and returns a sensible score for the
     cold-start demo and for CI where no trained model exists yet. The fallback reports
     model_version = "heuristic/fallback/v0" so it is never mistaken for a trained model
     in score history / A-B analysis.

Text -> 384-dim MiniLM embedding (full, no PCA — change #8), concatenated with the
ordered structured vector (see features.py) and fed to the tree model. SHAP TreeExplainer
gives per-feature contributions surfaced as top_factors (T3.7).
"""
from __future__ import annotations

import logging
import os
from typing import Optional

import numpy as np

from .features import EMBEDDING_DIM, assemble, feature_names, structured_vector

log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MLFLOW_MODEL_NAME", "leadscore")
MODEL_STAGE = os.getenv("MLFLOW_MODEL_STAGE", "Production")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
TOP_FACTORS_N = int(os.getenv("TOP_FACTORS_N", "3"))
HEURISTIC_VERSION = "heuristic/fallback/v0"


class Embedder:
    """Lazy sentence-transformers wrapper; falls back to a deterministic hash embedding
    when the library/model is unavailable (offline CI, cold start) so inference is still
    total and deterministic."""

    def __init__(self, name: str = EMBEDDING_MODEL):
        self._name = name
        self._model = None
        self._tried = False

    def _ensure(self):
        if self._tried:
            return
        self._tried = True
        try:
            from sentence_transformers import SentenceTransformer  # heavy import

            self._model = SentenceTransformer(self._name)
            log.info("Loaded embedding model %s", self._name)
        except Exception as e:  # noqa: BLE001 - any failure -> deterministic fallback
            log.warning("Embedding model unavailable (%s); using hash fallback", e)
            self._model = None

    def embed(self, texts: list[str]) -> np.ndarray:
        self._ensure()
        joined = " ".join(t for t in texts if t) or ""
        if self._model is not None:
            vec = self._model.encode([joined], normalize_embeddings=True)[0]
            return np.asarray(vec, dtype=np.float64)
        return _hash_embedding(joined)


def _hash_embedding(text: str) -> np.ndarray:
    """Deterministic pseudo-embedding: seed a RNG from a STABLE hash of the text. Not
    semantic, but stable across processes (uses hashlib, not the salted built-in hash())
    and dimensionally correct, so the service, its tests, and the training job all agree
    on the same fallback embedding for the same text."""
    import hashlib

    digest = hashlib.sha256(text.encode("utf-8")).digest()
    seed = int.from_bytes(digest[:8], "big") % (2**32)
    rng = np.random.default_rng(seed)
    v = rng.standard_normal(EMBEDDING_DIM)
    norm = np.linalg.norm(v)
    return v / norm if norm else v


class ScoringModel:
    def __init__(self):
        self.embedder = Embedder()
        self._booster = None
        self._explainer = None
        self.model_version: str = HEURISTIC_VERSION
        self._names = feature_names()

    def load(self):
        """Attempt to load the Production model from MLflow; else stay on heuristic."""
        try:
            import mlflow  # noqa: F401

            uri = f"models:/{MODEL_NAME}/{MODEL_STAGE}"
            from mlflow.tracking import MlflowClient

            client = MlflowClient()
            versions = client.get_latest_versions(MODEL_NAME, stages=[MODEL_STAGE])
            if not versions:
                log.warning("No %s-stage model for '%s'; using heuristic fallback", MODEL_STAGE, MODEL_NAME)
                return
            import mlflow.xgboost

            self._booster = mlflow.xgboost.load_model(uri)
            self.model_version = f"{MODEL_NAME}/{MODEL_STAGE}/v{versions[0].version}"
            self._init_explainer()
            log.info("Loaded model %s", self.model_version)
        except Exception as e:  # noqa: BLE001
            log.warning("MLflow load failed (%s); using heuristic fallback", e)
            self._booster = None
            self.model_version = HEURISTIC_VERSION

    def _init_explainer(self):
        try:
            import shap

            self._explainer = shap.TreeExplainer(self._booster)
        except Exception as e:  # noqa: BLE001
            log.warning("SHAP explainer unavailable (%s); top_factors from gain fallback", e)
            self._explainer = None

    def score(self, structured: dict, text_features: list[str]) -> tuple[float, list[dict]]:
        embedding = self.embedder.embed(text_features)
        row = assemble(structured, embedding)

        if self._booster is None:
            return self._heuristic_score(structured, row)

        import xgboost as xgb

        dm = xgb.DMatrix(row.reshape(1, -1), feature_names=self._names)
        prob = float(self._booster.predict(dm)[0])
        return prob, self._top_factors(row)

    def _top_factors(self, row: np.ndarray) -> list[dict]:
        if self._explainer is not None:
            try:
                contribs = np.asarray(self._explainer.shap_values(row.reshape(1, -1)))[0]
                order = np.argsort(-np.abs(contribs))[:TOP_FACTORS_N]
                return [
                    {"feature": self._names[i], "impact": round(float(contribs[i]), 4)}
                    for i in order
                ]
            except Exception as e:  # noqa: BLE001
                log.warning("SHAP evaluation failed (%s); empty top_factors", e)
        return []

    def _heuristic_score(self, structured: dict, row: np.ndarray) -> tuple[float, list[dict]]:
        """Transparent monotone heuristic used until a trained model is promoted. Clicks
        and opens push the score up; staleness pushes it down. Deterministic so tests can
        assert on it."""
        s = structured_vector(structured)
        opens, clicks, stale, velocity, size = s
        raw = 0.15 * opens + 0.30 * clicks - 0.05 * stale + 0.10 * size - 0.02 * (velocity / 30.0)
        score = 1.0 / (1.0 + np.exp(-(raw - 1.0)))  # squash to (0,1)
        factors = [
            {"feature": "email_click_count", "impact": round(0.30 * clicks, 4)},
            {"feature": "email_open_count", "impact": round(0.15 * opens, 4)},
            {"feature": "company_size_ordinal", "impact": round(0.10 * size, 4)},
        ]
        factors.sort(key=lambda f: abs(f["impact"]), reverse=True)
        return float(score), factors[:TOP_FACTORS_N]
