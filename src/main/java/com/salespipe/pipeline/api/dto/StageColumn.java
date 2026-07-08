package com.salespipe.pipeline.api.dto;

import java.util.List;
import java.util.UUID;

public record StageColumn(UUID stageId, String stageName, int position,
                          List<DealResponse> deals) {}
