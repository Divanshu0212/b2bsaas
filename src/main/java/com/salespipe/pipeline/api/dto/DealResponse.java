package com.salespipe.pipeline.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DealResponse(UUID id, UUID stageId, UUID leadId, UUID accountId,
                           UUID ownerId, BigDecimal amount, String currency,
                           LocalDate expectedCloseDate, int version) {}
