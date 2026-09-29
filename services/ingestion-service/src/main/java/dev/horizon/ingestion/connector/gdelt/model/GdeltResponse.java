package dev.horizon.ingestion.connector.gdelt.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ GDELT DOC 2.0 в режиме {@code artlist}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GdeltResponse(List<Article> articles) {

    public GdeltResponse {
        articles = articles == null ? List.of() : List.copyOf(articles);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Article(
            String url, String title, String seendate, String domain, String language, String sourcecountry) {}

    /** Одна статья вместе с происхождением ответа, в котором она пришла. */
    public record Raw(Article article, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
