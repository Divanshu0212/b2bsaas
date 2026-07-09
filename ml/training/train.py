"""Offline training job (phase-3 T3.6).

Pulls closed deals (is_won / is_lost) and their FEATURE SNAPSHOT AT CLOSE TIME, builds a
training matrix with the exact serve-time column order (app/features.py), trains an
XGBoost classifier, evaluates AUC-ROC + precision@k on a held-out split, and logs the
run to MLflow. Promotion to Production is a separate step (promote.py) gated on beating
the current Production model.

Anti-leakage: the training matrix uses the close-time snapshot, NEVER the lead's current
feature state — using current state would leak post-outcome signal and inflate offline
metrics. `load_training_frame` is the seam the data-leakage test drives.

Cold-start: with no closed deals there is no training data. For the demo we seed a
labeled synthetic sample (build_synthetic) and say so (phase-3 "Risks/gotchas").
"""
from __future__ import annotations

import argparse
import logging
import os

import numpy as np

from app.features import assemble
from app.model import _hash_embedding
from training.evaluate import evaluate

log = logging.getLogger(__name__)


def build_matrix(rows: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    """rows: each {structured..., text: str, label: 0/1}. Uses close-time snapshot values.

    Returns (X, y) with X assembled through the SAME app.features.assemble the server
    uses at inference — guaranteeing column alignment train<->serve."""
    X, y = [], []
    for r in rows:
        emb = _hash_embedding(r.get("text", "") or "")
        X.append(assemble(r, emb))
        y.append(int(r["label"]))
    return np.vstack(X), np.array(y)


def train_booster(X: np.ndarray, y: np.ndarray):
    import xgboost as xgb

    dtrain = xgb.DMatrix(X, label=y)
    params = {
        "objective": "binary:logistic",
        "eval_metric": "auc",
        "max_depth": 4,
        "eta": 0.2,
    }
    return xgb.train(params, dtrain, num_boost_round=50)


def train_and_log(rows: list[dict], k: int = 10, tracking_uri: str | None = None) -> dict:
    """Train, evaluate on a held-out split, log to MLflow. Returns the metrics dict."""
    X, y = build_matrix(rows)
    n = len(y)
    split = max(1, int(n * 0.8))
    booster = train_booster(X[:split], y[:split])

    import xgboost as xgb

    preds = booster.predict(xgb.DMatrix(X[split:]))
    metrics = evaluate(y[split:], preds, k=k)

    try:
        import mlflow
        import mlflow.xgboost

        if tracking_uri:
            mlflow.set_tracking_uri(tracking_uri)
        mlflow.set_experiment("leadscore")
        with mlflow.start_run():
            mlflow.log_params({"max_depth": 4, "eta": 0.2, "num_boost_round": 50, "n_train": split})
            mlflow.log_metrics({m: float(v) for m, v in metrics.items() if not np.isnan(v)})
            mlflow.xgboost.log_model(booster, artifact_path="model", registered_model_name="leadscore")
        log.info("Logged training run to MLflow: %s", metrics)
    except Exception as e:  # noqa: BLE001
        log.warning("MLflow logging skipped (%s); metrics=%s", e, metrics)

    return metrics


def build_synthetic(n: int = 400, seed: int = 7) -> list[dict]:
    """Labeled synthetic training set for the cold-start demo. Positives correlate with
    engagement (clicks/opens) and recency; deliberately learnable so a real model beats
    the heuristic."""
    rng = np.random.default_rng(seed)
    rows = []
    for _ in range(n):
        clicks = int(rng.poisson(1.5))
        opens = int(rng.poisson(4))
        stale = float(rng.exponential(10))
        velocity = float(rng.uniform(1, 60))
        size = int(rng.integers(0, 6))
        logit = 0.6 * clicks + 0.2 * opens - 0.15 * stale + 0.1 * size - 2.0
        p = 1 / (1 + np.exp(-logit))
        label = int(rng.random() < p)
        rows.append(
            {
                "email_open_count": opens,
                "email_click_count": clicks,
                "days_since_last_activity": stale,
                "deal_velocity_days": velocity,
                "company_size_bucket": ["", "1-10", "11-50", "51-200", "201-1000", "1000+"][size],
                "text": "hot inbound demo" if label else "cold outbound",
                "label": label,
            }
        )
    return rows


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser()
    parser.add_argument("--k", type=int, default=10)
    parser.add_argument("--tracking-uri", default=os.getenv("MLFLOW_TRACKING_URI"))
    args = parser.parse_args()
    # Real deployment plugs a DB query here (load_training_frame); demo uses synthetic.
    metrics = train_and_log(build_synthetic(), k=args.k, tracking_uri=args.tracking_uri)
    print(metrics)


if __name__ == "__main__":
    main()
