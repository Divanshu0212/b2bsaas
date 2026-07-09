"""Model promotion gate (phase-3 T3.6).

A newly-trained candidate is transitioned to Production ONLY if it beats the current
Production model on the held-out metric (AUC-ROC by default). A worse candidate is left
in None/Staging and Production is untouched — this is the auto-promote acceptance
criterion ("a better model auto-promotes; a worse one does not").

`should_promote` is the pure decision function the pytest promotion test drives without
touching MLflow; `promote_if_better` wires it to the MLflow registry.
"""
from __future__ import annotations

import logging

log = logging.getLogger(__name__)

PRIMARY_METRIC = "auc_roc"


def should_promote(candidate: dict, current: dict | None, metric: str = PRIMARY_METRIC) -> bool:
    """True iff the candidate strictly beats the current Production model on `metric`.
    No current model (first ever) -> promote. Missing metric on candidate -> do not."""
    cand = candidate.get(metric)
    if cand is None:
        return False
    if current is None:
        return True
    cur = current.get(metric)
    if cur is None:
        return True
    return cand > cur


def promote_if_better(model_name: str, candidate_version: str, candidate_metrics: dict,
                      metric: str = PRIMARY_METRIC) -> bool:
    """Transition candidate_version -> Production if it beats the incumbent. Returns the
    decision. Best-effort against MLflow; raises only on a genuine registry error."""
    from mlflow.tracking import MlflowClient

    client = MlflowClient()
    current_metrics = None
    prod = client.get_latest_versions(model_name, stages=["Production"])
    if prod:
        run = client.get_run(prod[0].run_id)
        current_metrics = run.data.metrics

    if not should_promote(candidate_metrics, current_metrics, metric):
        log.info("Candidate v%s did not beat Production on %s; not promoting",
                 candidate_version, metric)
        return False

    client.transition_model_version_stage(
        name=model_name,
        version=candidate_version,
        stage="Production",
        archive_existing_versions=True,
    )
    log.info("Promoted %s v%s to Production", model_name, candidate_version)
    return True
