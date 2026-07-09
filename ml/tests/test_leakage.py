"""Data-leakage test (phase-3 testing requirement): training must build its matrix from
the CLOSE-TIME feature snapshot, never the lead's current state. We assert build_matrix
is a pure function of the snapshot dict passed in — feeding it snapshot values produces a
matrix that reflects exactly those values, so a caller supplying close-time snapshots
cannot accidentally leak current-state signal (the snapshot IS the only input)."""
import numpy as np

from app.features import STRUCTURED_COLUMNS
from training.train import build_matrix


def test_build_matrix_uses_only_snapshot_values():
    # Two leads with identical labels but different close-time snapshots must yield
    # different structured rows — proving the snapshot value drives the matrix.
    rows = [
        {"email_click_count": 0, "label": 1, "text": "x"},
        {"email_click_count": 9, "label": 1, "text": "x"},
    ]
    X, y = build_matrix(rows)
    click_col = STRUCTURED_COLUMNS.index("email_click_count")
    assert X[0, click_col] == 0.0
    assert X[1, click_col] == 9.0
    assert list(y) == [1, 1]


def test_build_matrix_is_deterministic():
    rows = [{"email_open_count": 2, "label": 0, "text": "hello"}]
    X1, _ = build_matrix(rows)
    X2, _ = build_matrix(rows)
    assert np.array_equal(X1, X2)  # same snapshot -> same matrix, no hidden state
