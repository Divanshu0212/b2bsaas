package com.salespipe.scoring.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Request body for the Python scoring service's {@code POST /internal/score} (phase-3
 * T3.3). Field names are snake_case to match the Python Pydantic contract
 * ({@code ml/app/schemas.py}) exactly — the contract test asserts this shape on both
 * sides. {@code structuredFeatures} is an open map so adding a structured feature does
 * not require touching this DTO on every change.
 */
public record ScoreRequest(
    @JsonProperty("lead_id") String leadId,
    @JsonProperty("text_features") List<String> textFeatures,
    @JsonProperty("structured_features") Map<String, Object> structuredFeatures
) {
}
