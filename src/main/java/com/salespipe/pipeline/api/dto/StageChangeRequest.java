package com.salespipe.pipeline.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StageChangeRequest(@NotNull UUID toStageId, @NotNull Integer version) {}
