package com.salespipe.scoring.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response body from {@code POST /internal/score} (phase-3 T3.3). Mirrors {@code
 * ScoreResponse} in {@code ml/app/schemas.py}. {@code modelVersion} is the MLflow
 * name/stage/version string persisted onto every {@code lead_scores} row so A/B and
 * rollback analysis works (phase-3 "Risks/gotchas": keep this string consistent across
 * MLflow, the score row, and the API response).
 */
public record ScoreResponse(
    double score,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("top_factors") List<TopFactor> topFactors
) {
    public record TopFactor(String feature, double impact) {}
}
