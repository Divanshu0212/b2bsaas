"""Metric correctness (phase-3 T3.6 testing requirement: precision@k / AUC computation)."""
import numpy as np

from training.evaluate import auc_roc, evaluate, precision_at_k


def test_auc_perfect_separation():
    y = np.array([0, 0, 1, 1])
    s = np.array([0.1, 0.2, 0.8, 0.9])
    assert auc_roc(y, s) == 1.0


def test_auc_inverted_is_zero():
    y = np.array([0, 0, 1, 1])
    s = np.array([0.9, 0.8, 0.2, 0.1])
    assert auc_roc(y, s) == 0.0


def test_auc_random_is_half_with_ties():
    y = np.array([0, 1, 0, 1])
    s = np.array([0.5, 0.5, 0.5, 0.5])  # all tied -> AUC 0.5
    assert abs(auc_roc(y, s) - 0.5) < 1e-9


def test_precision_at_k_top_slice():
    y = np.array([1, 0, 1, 0, 1])
    s = np.array([0.9, 0.1, 0.8, 0.2, 0.7])
    # top-3 scored are indices 0,2,4 -> all positive
    assert precision_at_k(y, s, 3) == 1.0


def test_precision_at_k_clamps_k():
    y = np.array([1, 0])
    s = np.array([0.9, 0.1])
    assert precision_at_k(y, s, 10) == 0.5  # k clamped to 2


def test_evaluate_returns_both_metrics():
    y = np.array([0, 1, 1, 0])
    s = np.array([0.2, 0.9, 0.7, 0.1])
    m = evaluate(y, s, k=2)
    assert "auc_roc" in m and "precision_at_2" in m
