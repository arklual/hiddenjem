package dev.horizon.trends.domain.report;

/** Detected attention burst (methodology §5). Null when the trend never burst. */
public record Burst(String startPeriod, Double weight) {}
