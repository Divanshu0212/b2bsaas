"""One-shot model seeder for local/compose bring-up.

Trains a candidate on the synthetic cold-start set (train.py), then promotes the newest
registered version to Production via the normal gate (promote.py) so the scoring service
loads a real model on first boot instead of the heuristic fallback.

Idempotent-ish: re-running trains a new version and promotes it only if it beats the
incumbent (the gate). Safe to run repeatedly. Intended to run as a compose init job or via
`make seed-model`. Reads MLFLOW_TRACKING_URI from the environment.
"""
from __future__ import annotations

import logging
import os

from training.train import build_synthetic, train_and_log
from training.promote import promote_if_better

log = logging.getLogger("seed")


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    tracking_uri = os.getenv("MLFLOW_TRACKING_URI")
    model_name = os.getenv("MLFLOW_MODEL_NAME", "leadscore")

    # 1. Train + register a candidate from the synthetic cold-start set.
    metrics = train_and_log(build_synthetic(), tracking_uri=tracking_uri)
    log.info("trained candidate: %s", metrics)

    # 2. Find the newest registered version and run it through the promotion gate.
    from mlflow.tracking import MlflowClient

    if tracking_uri:
        import mlflow

        mlflow.set_tracking_uri(tracking_uri)
    client = MlflowClient()
    versions = client.search_model_versions(f"name = '{model_name}'")
    if not versions:
        log.warning("no registered versions for '%s'; nothing to promote", model_name)
        return
    newest = max(versions, key=lambda v: int(v.version))

    promoted = promote_if_better(model_name, newest.version, metrics)
    log.info("version %s promoted=%s", newest.version, promoted)


if __name__ == "__main__":
    main()
