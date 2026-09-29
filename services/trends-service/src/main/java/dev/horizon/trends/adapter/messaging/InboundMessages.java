package dev.horizon.trends.adapter.messaging;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound wire shapes consumed by this service.
 *
 * <p>Each record mirrors a file in {@code contracts/schemas/} and is the anti-corruption boundary for
 * it: the saga is given domain values, never a producer's DTO, so ingestion or the Python engine can
 * evolve their JSON without the process manager noticing.
 *
 * <p>{@code ignoreUnknown = true} throughout — additive producer changes must not break a consumer
 * within a topic major version, which is the compatibility rule the AsyncAPI contract states.
 */
public final class InboundMessages {

    private InboundMessages() {}

    public static final String TYPE_CORPUS_COLLECTED = "horizon.ingestion.CorpusCollected";
    public static final String TYPE_CORPUS_COLLECTION_FAILED = "horizon.ingestion.CorpusCollectionFailed";
    public static final String TYPE_CORPUS_COLLECTION_PROGRESSED = "horizon.ingestion.CorpusCollectionProgressed";
    public static final String TYPE_DOMAIN_ANALYZED = "horizon.analysis.DomainAnalyzed";
    public static final String TYPE_DOMAIN_ANALYSIS_FAILED = "horizon.analysis.DomainAnalysisFailed";
    public static final String TYPE_ANALYSIS_PROGRESSED = "horizon.analysis.AnalysisProgressed";

    /** {@code contracts/schemas/corpus-collected.event.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorpusCollected(
            String researchRequestId,
            int attempt,
            String snapshotId,
            int documentCount,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            LocalDate windowFrom,
            LocalDate windowTo,
            String contentHash) {

        public List<String> sourcesUsedOrEmpty() {
            return sourcesUsed == null ? List.of() : sourcesUsed;
        }

        public List<String> unavailableSourcesOrEmpty() {
            return unavailableSources == null ? List.of() : unavailableSources;
        }
    }

    /** {@code contracts/schemas/corpus-collection-progressed.event.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorpusCollectionProgressed(String researchRequestId, int attempt, int percent, String message) {}

    /** {@code contracts/schemas/analysis-progressed.event.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalysisProgressed(
            String researchRequestId, int attempt, String stage, int percent, String message) {}

    /** {@code contracts/schemas/failure.event.json} — shared by every failing saga step. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String researchRequestId, int attempt, String code, String message, boolean retryable) {}
}
