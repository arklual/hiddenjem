package dev.horizon.ingestion.connector.producthunt.model;

import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Запуск продукта так, как его отдала лента Product Hunt.
 *
 * @param category рубрика Product Hunt, чья лента принесла запуск; {@code null} — общая лента
 */
public record ProductLaunch(
        SearchFeeds.Entry entry, String category, String sourceId, String externalId, Provenance provenance)
        implements RawDocument {}
