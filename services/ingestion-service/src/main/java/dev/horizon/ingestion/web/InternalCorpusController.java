package dev.horizon.ingestion.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.ingestion.domain.port.CorpusSnapshotRepository;
import dev.horizon.platform.common.error.HorizonException;

/**
 * Internal read API used by the analytics engine to resolve a snapshot into its documents.
 *
 * <p>Deliberately synchronous. The rule the architecture enforces is "no service reads another
 * service's database" (ADR-0006) — not "no service ever calls another". Commands stay asynchronous
 * because they are long-running and must survive an outage; a query for an immutable, already-known
 * document set has neither property, and pushing a 5000-element id list through Kafka to avoid one
 * HTTP call would be ceremony, not design.
 *
 * <p>Mounted under {@code /internal} and not routed by the gateway: it is cluster-internal surface,
 * protected by network policy rather than by a user token.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalCorpusController {

    private static final int MAX_PAGE = 5_000;

    private final CorpusSnapshotRepository snapshots;

    public InternalCorpusController(CorpusSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** @return snapshot metadata without the (potentially large) document list */
    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotView get(@PathVariable UUID snapshotId) {
        var snapshot = snapshots
                .findById(snapshotId)
                .orElseThrow(() -> HorizonException.notFound("Снапшот корпуса", snapshotId));
        return new SnapshotView(
                snapshot.id(),
                snapshot.normalizedQuery(),
                snapshot.windowFrom().toString(),
                snapshot.windowTo().toString(),
                snapshot.documentIds().size(),
                snapshot.sourcesUsed(),
                snapshot.unavailableSources(),
                snapshot.partial(),
                snapshot.contentHash());
    }

    /** Paged document ids in the stable order that defines the snapshot's content hash. */
    @GetMapping("/snapshots/{snapshotId}/documents")
    public DocumentIdsView documents(
            @PathVariable UUID snapshotId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "1000") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        List<UUID> ids = snapshots.findDocumentIds(snapshotId, Math.max(offset, 0), safeLimit);
        return new DocumentIdsView(snapshotId, Math.max(offset, 0), safeLimit, ids);
    }

    public record SnapshotView(
            UUID id,
            String normalizedQuery,
            String windowFrom,
            String windowTo,
            int documentCount,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            String contentHash) {}

    public record DocumentIdsView(UUID snapshotId, int offset, int limit, List<UUID> documentIds) {}
}
