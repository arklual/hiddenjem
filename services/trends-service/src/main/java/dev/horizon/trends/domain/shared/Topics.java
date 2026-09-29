package dev.horizon.trends.domain.shared;

/** Topic names from {@code contracts/asyncapi/horizon-events.yaml}. Single source of truth in code. */
public final class Topics {

    public static final String INGESTION_COMMANDS = "horizon.ingestion.commands.v1";
    public static final String INGESTION_EVENTS = "horizon.ingestion.events.v1";
    public static final String ANALYSIS_COMMANDS = "horizon.analysis.commands.v1";
    public static final String ANALYSIS_EVENTS = "horizon.analysis.events.v1";
    public static final String TRENDS_EVENTS = "horizon.trends.events.v1";

    private Topics() {}
}
