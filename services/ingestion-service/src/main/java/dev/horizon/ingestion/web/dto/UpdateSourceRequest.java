package dev.horizon.ingestion.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Partial update of a source; {@code null} means "leave unchanged". */
public record UpdateSourceRequest(Boolean enabled, @Min(1) @Max(6000) Integer rateLimitPerMinute) {}
