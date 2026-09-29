package dev.horizon.ingestion.domain.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Archive of the untouched responses sources returned (FR-04.5, BR-C5).
 *
 * <p>Why keep them at all: when a normalizer is found to be wrong six months later, the archived
 * payload is the only way to re-derive the corpus without re-crawling — and for an audit it is the
 * evidence that a document said what we claim it said.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@code FilesystemRawPayloadStore} — development and single-node deployments; writes under a
 *       configured root with a {@code source/date/hash} layout.
 *   <li>{@code NoopRawPayloadStore} — explicit opt-out for tests and throw-away environments;
 *       returns an empty reference so callers behave identically.
 *   <li><b>S3-compatible store — the production adapter.</b> It is not implemented here on purpose:
 *       adding an object-storage SDK to this service now would be a dependency without a caller.
 *       Plugging it in means one class implementing this interface (the natural key is exactly the
 *       one the filesystem adapter builds, {@code raw/{sourceId}/{yyyy}/{MM}/{dd}/{payloadHash}.json})
 *       and one {@code @ConditionalOnProperty} bean in the same configuration class — no change
 *       above this port, and lifecycle/retention (90 days, §6 of the data model) becomes a bucket
 *       policy instead of a cron job.
 * </ul>
 *
 * <p>Archiving must never fail a collection: a store that cannot write returns
 * {@link Optional#empty()} and the document is persisted with {@code rawRef = null}.
 */
public interface RawPayloadStore {

    /**
     * Archives one raw response.
     *
     * @param sourceId connector that produced the payload
     * @param payloadHash SHA-256 of the payload, also its content address
     * @param fetchedAt when it was fetched — drives the storage layout and the retention policy
     * @param payload the bytes as received
     * @param contentType MIME type, used as the file extension hint
     * @return storage reference to persist in {@code documents.raw_ref}, or empty when archiving is
     *     disabled or impossible
     */
    Optional<String> archive(
            String sourceId, String payloadHash, Instant fetchedAt, byte[] payload, String contentType);
}
