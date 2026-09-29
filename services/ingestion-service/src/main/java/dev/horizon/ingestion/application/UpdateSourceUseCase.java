package dev.horizon.ingestion.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.DocumentRepository;
import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.source.Source;
import dev.horizon.platform.common.error.HorizonException;

/**
 * UC-11: change a source's operational policy.
 *
 * <p>The new rate limit is pushed into the live limiter registry in the same call, so throttling a
 * source that is hammering an upstream takes effect on the next request rather than on the next
 * deployment.
 */
@Service
public class UpdateSourceUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateSourceUseCase.class);

    private final SourceRepository sources;
    private final IngestionRunRepository runs;
    private final DocumentRepository documents;
    private final ConnectorCatalog catalog;
    private final RateLimiters rateLimiters;

    public UpdateSourceUseCase(
            SourceRepository sources,
            IngestionRunRepository runs,
            DocumentRepository documents,
            ConnectorCatalog catalog,
            RateLimiters rateLimiters) {
        this.sources = sources;
        this.runs = runs;
        this.documents = documents;
        this.catalog = catalog;
        this.rateLimiters = rateLimiters;
    }

    @Transactional
    public SourceSummary update(String sourceId, Boolean enabled, Integer rateLimitPerMinute) {
        Source source = sources.findById(sourceId).orElseThrow(() -> HorizonException.notFound("Источник", sourceId));
        source.changeEnabled(enabled);
        source.changeRateLimit(rateLimitPerMinute);
        Source saved = sources.save(source);
        rateLimiters.reconfigure(saved.id(), saved.rateLimitPerMinute());
        log.info(
                "Source {} updated: enabled={} rateLimit={}/min",
                saved.id(),
                saved.isEnabled(),
                saved.rateLimitPerMinute());

        boolean credentials = catalog.byId(saved.id())
                .map(connector -> connector.descriptor().credentialsConfigured())
                .orElse(false);
        return new SourceSummary(
                saved,
                credentials,
                documents.countBySourceId(saved.id()),
                runs.findLatest(saved.id()).orElse(null));
    }
}
