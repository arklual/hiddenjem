package dev.horizon.ingestion.connector.support;

import java.time.Instant;
import java.util.Optional;

import dev.horizon.ingestion.domain.port.RawPayloadStore;

/**
 * <b>Archiving disabled.</b> Explicitly does nothing and says so.
 *
 * <p>Selected by {@code horizon.connectors.raw-payload-store.mode=none}. Legitimate for tests and
 * throw-away environments; in production it forfeits FR-04.5 / BR-C5 (documents are stored with
 * {@code raw_ref = null} and the original response is unrecoverable), which is why the choice must
 * be made in configuration and not by default.
 */
public class NoopRawPayloadStore implements RawPayloadStore {

    @Override
    public Optional<String> archive(
            String sourceId, String payloadHash, Instant fetchedAt, byte[] payload, String contentType) {
        return Optional.empty();
    }
}
