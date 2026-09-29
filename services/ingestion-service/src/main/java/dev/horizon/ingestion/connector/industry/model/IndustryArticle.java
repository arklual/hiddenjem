package dev.horizon.ingestion.connector.industry.model;

import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Статья отраслевого издания из его поисковой ленты.
 *
 * @param outlet название издания из заголовка ленты — оно же организация, стоящая за публикацией
 */
public record IndustryArticle(
        SearchFeeds.Entry entry, String outlet, String sourceId, String externalId, Provenance provenance)
        implements RawDocument {}
