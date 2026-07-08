package com.salespipe.crmcore.api.dto;

import com.salespipe.crmcore.domain.LeadStatus;
import java.util.UUID;

public record LeadResponse(
    UUID id, LeadStatus status, String source, String rawNotes,
    UUID contactId, UUID accountId, UUID ownerId, int version) {}
