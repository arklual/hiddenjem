package dev.horizon.ingestion.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Manual run request. */
public record TriggerIngestionRequest(
        @Pattern(regexp = "INCREMENTAL|BACKFILL") String mode,
        @Size(max = 200) String query,
        LocalDate windowFrom,
        LocalDate windowTo) {

    public static TriggerIngestionRequest defaults() {
        return new TriggerIngestionRequest("INCREMENTAL", null, null, null);
    }
}
