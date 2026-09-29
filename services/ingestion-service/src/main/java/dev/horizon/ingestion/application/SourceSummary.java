package dev.horizon.ingestion.application;

import dev.horizon.ingestion.domain.run.IngestionRun;
import dev.horizon.ingestion.domain.source.Source;

/**
 * Read model behind {@code GET /api/v1/sources}: the configured source plus the two things an
 * operator actually wants to know — is it usable right now, and what did it last do.
 *
 * @param apiKeyConfigured whether the credential the connector needs is present in the environment;
 *     reported separately from {@code requiresApiKey} so the UI can say "needs a key" and "has a
 *     key" independently
 */
public record SourceSummary(Source source, boolean apiKeyConfigured, long documentCount, IngestionRun lastRun) {

    public boolean usable() {
        return source.isUsable(apiKeyConfigured);
    }
}
