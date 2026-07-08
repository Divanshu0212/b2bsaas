package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record ContactRequest(UUID accountId, String firstName, String lastName,
                             String email, String phone, String title) {}
