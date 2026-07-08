package com.salespipe.crmcore.api.dto;

import com.salespipe.crmcore.domain.LeadStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeadRequest(
    @NotNull LeadStatus status, String source, String rawNotes,
    UUID contactId, UUID accountId, UUID ownerId) {}
