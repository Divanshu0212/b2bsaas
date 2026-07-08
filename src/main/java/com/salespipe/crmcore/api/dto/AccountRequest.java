package com.salespipe.crmcore.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountRequest(@NotBlank String name, String industry,
                             Integer employeeCount, String website) {}
