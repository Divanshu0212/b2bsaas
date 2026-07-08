package com.salespipe.activity.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ActivityResponse(
    UUID id,
    String entityType,
    UUID entityId,
    String activityType,
    JsonNode payload,
    UUID createdBy,
    OffsetDateTime createdAt
) {}
