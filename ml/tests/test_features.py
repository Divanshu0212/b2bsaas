"""Feature-vector correctness + train/serve column alignment (phase-3 T3.6 + data-leakage
discipline). The training matrix and serve-time vector MUST use the same assemble()."""
import numpy as np

from app.features import (
    EMBEDDING_DIM,
    STRUCTURED_COLUMNS,
    assemble,
    feature_names,
    size_ordinal,
    structured_vector,
)


def test_structured_vector_order_and_missing_defaults():
    v = structured_vector({"email_open_count": 3, "email_click_count": 1})
    assert v.shape == (len(STRUCTURED_COLUMNS),)
    assert v[0] == 3.0 and v[1] == 1.0
    assert v[2] == 0.0 and v[3] == 0.0 and v[4] == 0.0  # missing -> 0


def test_size_ordinal_mapping():
    assert size_ordinal("1-10") == 1
    assert size_ordinal("1000+") == 5
    assert size_ordinal(None) == 0
    assert size_ordinal("unknown") == 0


def test_assemble_dim_is_structured_plus_embedding():
    emb = np.zeros(EMBEDDING_DIM)
    row = assemble({"email_open_count": 1}, emb)
    assert row.shape == (len(STRUCTURED_COLUMNS) + EMBEDDING_DIM,)


def test_assemble_rejects_wrong_embedding_dim():
    try:
        assemble({}, np.zeros(10))
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_feature_names_align_with_vector_length():
    emb = np.zeros(EMBEDDING_DIM)
    row = assemble({}, emb)
    assert len(feature_names()) == row.shape[0]
