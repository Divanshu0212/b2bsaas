"""Promotion gate: better model only (phase-3 T3.6 accept: a better model auto-promotes,
a worse one does not)."""
from training.promote import should_promote


def test_promotes_when_no_incumbent():
    assert should_promote({"auc_roc": 0.6}, None) is True


def test_promotes_when_strictly_better():
    assert should_promote({"auc_roc": 0.81}, {"auc_roc": 0.79}) is True


def test_does_not_promote_when_worse():
    assert should_promote({"auc_roc": 0.70}, {"auc_roc": 0.79}) is False


def test_does_not_promote_when_equal():
    # must strictly beat — a tie is not an improvement.
    assert should_promote({"auc_roc": 0.79}, {"auc_roc": 0.79}) is False


def test_does_not_promote_without_candidate_metric():
    assert should_promote({}, {"auc_roc": 0.5}) is False
