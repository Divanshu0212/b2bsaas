"""Offline evaluation metrics (phase-3 T3.6).

AUC-ROC + precision@k. precision@k matters more than raw accuracy because reps act on
the top-N leads only (phase-3 talking points) — a model that ranks the truly-hot leads
into the top slice is what's useful, regardless of global accuracy.
"""
from __future__ import annotations

import numpy as np


def auc_roc(y_true: np.ndarray, y_score: np.ndarray) -> float:
    """AUC via the rank-sum (Mann-Whitney U) identity; no sklearn dependency needed."""
    y_true = np.asarray(y_true)
    y_score = np.asarray(y_score)
    pos = y_score[y_true == 1]
    neg = y_score[y_true == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    order = np.argsort(y_score)
    ranks = np.empty_like(order, dtype=float)
    ranks[order] = np.arange(1, len(y_score) + 1)
    # average ties
    _average_ties(y_score, ranks)
    rank_sum_pos = ranks[y_true == 1].sum()
    n_pos, n_neg = len(pos), len(neg)
    return float((rank_sum_pos - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg))


def _average_ties(scores: np.ndarray, ranks: np.ndarray) -> None:
    order = np.argsort(scores)
    sorted_scores = scores[order]
    i = 0
    n = len(scores)
    while i < n:
        j = i
        while j + 1 < n and sorted_scores[j + 1] == sorted_scores[i]:
            j += 1
        if j > i:
            avg = ranks[order[i : j + 1]].mean()
            ranks[order[i : j + 1]] = avg
        i = j + 1


def precision_at_k(y_true: np.ndarray, y_score: np.ndarray, k: int) -> float:
    """Fraction of the top-k highest-scored items that are actually positive."""
    y_true = np.asarray(y_true)
    y_score = np.asarray(y_score)
    k = min(k, len(y_score))
    if k == 0:
        return float("nan")
    top = np.argsort(-y_score)[:k]
    return float(y_true[top].sum() / k)


def evaluate(y_true: np.ndarray, y_score: np.ndarray, k: int = 10) -> dict:
    return {
        "auc_roc": auc_roc(y_true, y_score),
        f"precision_at_{k}": precision_at_k(y_true, y_score, k),
    }
