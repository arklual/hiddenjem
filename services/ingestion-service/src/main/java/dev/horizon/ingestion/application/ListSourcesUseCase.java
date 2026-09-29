package dev.horizon.ingestion.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.DocumentRepository;
import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.source.Source;

/** UC-11: what sources exist, whether they can run, and what they last did. */
@Service
public class ListSourcesUseCase {

    private final SourceRepository sources;
    private final IngestionRunRepository runs;
    private final DocumentRepository documents;
    private final ConnectorCatalog catalog;

    public ListSourcesUseCase(
            SourceRepository sources,
            IngestionRunRepository runs,
            DocumentRepository documents,
            ConnectorCatalog catalog) {
        this.sources = sources;
        this.runs = runs;
        this.documents = documents;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<SourceSummary> list() {
        List<SourceSummary> summaries = new ArrayList<>();
        for (Source source : sources.findAll()) {
            boolean credentials = catalog.byId(source.id())
                    .map(connector -> connector.descriptor().credentialsConfigured())
                    .orElse(false);
            summaries.add(new SourceSummary(
                    source,
                    credentials,
                    documents.countBySourceId(source.id()),
                    runs.findLatest(source.id()).orElse(null)));
        }
        summaries.sort(Comparator.comparing(summary -> summary.source().id()));
        return List.copyOf(summaries);
    }
}
