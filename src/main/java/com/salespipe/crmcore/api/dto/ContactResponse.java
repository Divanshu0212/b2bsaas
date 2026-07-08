package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record ContactResponse(UUID id, UUID accountId, String firstName,
                              String lastName, String email, String phone, String title) {}
