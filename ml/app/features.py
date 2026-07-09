"""Feature-vector assembly shared by serving (`model.py`) and training (`training/`).

Keeping this in one module is the anti-leakage discipline the phase-3 plan calls out:
training builds its matrix with the exact same column order + encoders the server uses
at inference, so "the feature that was column 12 at train time is column 12 at serve
time". The structured columns are deterministic and ordered by STRUCTURED_COLUMNS.
"""
from __future__ import annotations

from typing import Optional

import numpy as np

# Fixed, ordered structured feature columns. Order is the contract between train + serve.
STRUCTURED_COLUMNS = [
    "email_open_count",
    "email_click_count",
    "days_since_last_activity",
    "deal_velocity_days",
    "company_size_ordinal",
]

# Coarse company-size buckets -> ordinal. Unknown -> 0.
_SIZE_ORDINAL = {
    "1-10": 1,
    "11-50": 2,
    "51-200": 3,
    "201-1000": 4,
    "1000+": 5,
}

EMBEDDING_DIM = 384  # all-MiniLM-L6-v2 output dim; full embedding fed to the tree (change #8)


def size_ordinal(bucket: Optional[str]) -> int:
    return _SIZE_ORDINAL.get(bucket or "", 0)


def structured_vector(feats: dict) -> np.ndarray:
    """Ordered structured feature row from a plain dict (missing -> 0.0)."""
    return np.array(
        [
            float(feats.get("email_open_count") or 0),
            float(feats.get("email_click_count") or 0),
            float(feats.get("days_since_last_activity") or 0.0),
            float(feats.get("deal_velocity_days") or 0.0),
            float(size_ordinal(feats.get("company_size_bucket"))),
        ],
        dtype=np.float64,
    )


def feature_names() -> list[str]:
    """Column names for the full concatenated vector (structured + embedding dims)."""
    return STRUCTURED_COLUMNS + [f"emb_{i}" for i in range(EMBEDDING_DIM)]


def assemble(structured: dict, embedding: np.ndarray) -> np.ndarray:
    """Concatenate structured row + text embedding into one model input row."""
    if embedding.shape[0] != EMBEDDING_DIM:
        raise ValueError(f"embedding dim {embedding.shape[0]} != expected {EMBEDDING_DIM}")
    return np.concatenate([structured_vector(structured), embedding])
