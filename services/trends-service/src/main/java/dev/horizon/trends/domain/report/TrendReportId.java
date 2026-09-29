package dev.horizon.trends.domain.report;

import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;

/** Typed identifier of an immutable trend report. */
public record TrendReportId(UUID value) {

    public TrendReportId {
        Guards.requireNonNull(value, "trendReportId");
    }

    public static TrendReportId generate() {
        return new TrendReportId(Uuid7.randomUuid7());
    }

    public static TrendReportId of(String value) {
        return new TrendReportId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
