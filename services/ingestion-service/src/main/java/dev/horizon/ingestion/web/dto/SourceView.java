package dev.horizon.ingestion.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.ingestion.application.SourceSummary;

/**
 * Wire shape of a source, matching {@code SourceView} in the OpenAPI contract.
 *
 * <p>{@code lastRun} у источника, который ещё ни разу не собирался, отсутствует, а не равен
 * {@code null}: контракт объявляет поле необязательным, но не допускающим {@code null}. Прежде
 * сериализовался {@code null}, и экран «Источники данных» целиком падал на проверке формата, как
 * только в реестре появлялись новые коннекторы (стенд 2026-09-28).
 */
public record SourceView(
        String id,
        String displayName,
        String sourceClass,
        boolean enabled,
        boolean requiresApiKey,
        boolean apiKeyConfigured,
        int rateLimitPerMinute,
        long documentCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) IngestionRunView lastRun) {

    public static SourceView from(SourceSummary summary) {
        var source = summary.source();
        return new SourceView(
                source.id(),
                source.displayName(),
                source.sourceClass().name(),
                source.isEnabled(),
                source.requiresApiKey(),
                summary.apiKeyConfigured(),
                source.rateLimitPerMinute(),
                summary.documentCount(),
                summary.lastRun() == null ? null : IngestionRunView.from(summary.lastRun()));
    }
}
