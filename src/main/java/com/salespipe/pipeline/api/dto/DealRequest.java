package com.salespipe.pipeline.api.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DealRequest(@NotNull UUID stageId, UUID leadId, UUID accountId,
                          UUID ownerId, BigDecimal amount, String currency,
                          LocalDate expectedCloseDate) {}
