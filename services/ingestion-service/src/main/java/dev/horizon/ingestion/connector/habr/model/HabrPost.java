package dev.horizon.ingestion.connector.habr.model;

import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Публикация Хабра так, как её отдала поисковая лента. */
public record HabrPost(SearchFeeds.Entry entry, String sourceId, String externalId, Provenance provenance)
        implements RawDocument {}
