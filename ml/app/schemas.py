"""Pydantic request/response models for the scoring service (phase-3 T3.3).

The contract here is the single source of truth for BOTH sides: the Java WireMock
contract test (`ScoringClientContractTest`) asserts against this exact shape, and
`tests/test_contract.py` validates it Python-side. Keep field names in lockstep with
`src/main/resources/eventing/schema/lead.score.updated.json`'s producer payload and the
Java `ScoreResponse` DTO, or A/B analysis keyed on `model_version` breaks (see the
phase-3 "Risks/gotchas").
"""
from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class StructuredFeatures(BaseModel):
    email_open_count: int = 0
    email_click_count: int = 0
    days_since_last_activity: Optional[float] = None
    deal_velocity_days: Optional[float] = None
    company_size_bucket: Optional[str] = None
    industry: Optional[str] = None
    source: Optional[str] = None


class ScoreRequest(BaseModel):
    lead_id: str
    text_features: list[str] = Field(default_factory=list)
    structured_features: StructuredFeatures = Field(default_factory=StructuredFeatures)


class TopFactor(BaseModel):
    feature: str
    impact: float


class ScoreResponse(BaseModel):
    score: float = Field(ge=0.0, le=1.0)
    model_version: str
    top_factors: list[TopFactor]


class HealthResponse(BaseModel):
    status: str
    model_version: Optional[str] = None
