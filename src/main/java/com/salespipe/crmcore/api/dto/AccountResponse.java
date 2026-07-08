package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record AccountResponse(UUID id, String name, String industry,
                              Integer employeeCount, String website) {}
