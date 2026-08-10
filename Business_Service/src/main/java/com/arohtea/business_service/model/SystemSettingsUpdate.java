package com.arohtea.business_service.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SystemSettingsUpdate(
        @Size(max = 512) String apiKey,
        @Size(max = 100) String model,
        @Min(1) @Max(8) Integer mapWorkers,
        @Min(500) @Max(8000) Integer chunkSize,
        @Min(0) @Max(8000) Integer chunkOverlap) {
}
