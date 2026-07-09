"""Score API contract + inference determinism (phase-3 T3.3/T3.2 accept). Runs against
the heuristic fallback model (no MLflow/xgboost needed) so it is self-contained in CI."""
from fastapi.testclient import TestClient

from app.main import app
from app.schemas import ScoreResponse


def _client():
    # TestClient triggers lifespan -> model.load() (falls back to heuristic offline).
    return TestClient(app)


def test_health_reports_model_version():
    with _client() as c:
        r = c.get("/health")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "UP"
        assert body["model_version"]  # non-empty


def test_score_contract_shape():
    payload = {
        "lead_id": "11111111-1111-1111-1111-111111111111",
        "text_features": ["hot inbound lead, asked for pricing"],
        "structured_features": {
            "email_open_count": 5,
            "email_click_count": 3,
            "days_since_last_activity": 1,
            "deal_velocity_days": 14,
            "company_size_bucket": "51-200",
            "industry": "fintech",
            "source": "inbound",
        },
    }
    with _client() as c:
        r = c.post("/internal/score", json=payload)
        assert r.status_code == 200
        # Validates against the exact Pydantic response model (the contract).
        parsed = ScoreResponse.model_validate(r.json())
        assert 0.0 <= parsed.score <= 1.0
        assert parsed.model_version
        assert isinstance(parsed.top_factors, list)
        for f in parsed.top_factors:
            assert f.feature and isinstance(f.impact, float)


def test_inference_is_deterministic():
    payload = {
        "lead_id": "22222222-2222-2222-2222-222222222222",
        "text_features": ["same text"],
        "structured_features": {"email_open_count": 2, "email_click_count": 1},
    }
    with _client() as c:
        a = c.post("/internal/score", json=payload).json()
        b = c.post("/internal/score", json=payload).json()
        assert a["score"] == b["score"]  # same input -> same score


def test_engagement_raises_score():
    hot = {
        "lead_id": "33333333-3333-3333-3333-333333333333",
        "text_features": [""],
        "structured_features": {"email_open_count": 20, "email_click_count": 15,
                                "company_size_bucket": "1000+"},
    }
    cold = {
        "lead_id": "44444444-4444-4444-4444-444444444444",
        "text_features": [""],
        "structured_features": {"email_open_count": 0, "email_click_count": 0,
                                "days_since_last_activity": 60},
    }
    with _client() as c:
        hot_score = c.post("/internal/score", json=hot).json()["score"]
        cold_score = c.post("/internal/score", json=cold).json()["score"]
        assert hot_score > cold_score
