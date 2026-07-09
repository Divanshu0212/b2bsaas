package com.salespipe.scoring.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /leads/{id}/score} response (T3.5): the latest score plus its history tail.
 * {@code latest} is null when the lead has never been scored.
 */
public record ScoreResponseDto(
    UUID leadId,
    ScorePoint latest,
    List<ScorePoint> history
) {
    public record ScorePoint(
        BigDecimal score,
        String modelVersion,
        JsonNode topFactors,
        OffsetDateTime scoredAt
    ) {}
}
