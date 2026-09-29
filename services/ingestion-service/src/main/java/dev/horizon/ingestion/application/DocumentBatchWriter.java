package dev.horizon.ingestion.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.event.DocumentIngested;
import dev.horizon.ingestion.domain.port.DocumentRepository;
import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.event.DomainEventPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Writes one page of documents and their {@code DocumentIngested} events in a single transaction
 * (ADR-0003, FR-04.8).
 *
 * <p>The transaction boundary is the <em>page</em>, not the run: it keeps database connections out
 * of the network I/O path, and it is exactly the unit whose commit lets the cursor advance.
 *
 * <p>Deduplication runs in three layers, cheapest first:
 *
 * <ol>
 *   <li>within the page, by {@code dedupKey} — a source can return the same work twice;
 *   <li>against the database by {@code (sourceId, externalId)} and by {@code dedupKey};
 *   <li>the unique indexes themselves, which are the only layer that is safe under concurrency.
 * </ol>
 *
 * A duplicate is not an error: its existing id is returned so the snapshot still counts the
 * document, and no second event is published.
 */
@Service
public class DocumentBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(DocumentBatchWriter.class);

    private final DocumentRepository documents;
    private final DomainEventPublisher publisher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public DocumentBatchWriter(
            DocumentRepository documents, DomainEventPublisher publisher, Clock clock, MeterRegistry meterRegistry) {
        this.documents = documents;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public BatchResult write(List<Document> page) {
        if (page.isEmpty()) {
            return BatchResult.EMPTY;
        }
        var now = clock.instant();
        var events = new ArrayList<DomainEvent>(page.size());
        var documentIds = new ArrayList<UUID>(page.size());
        var seenInPage = new HashSet<String>(page.size() * 2);
        int created = 0;
        int duplicates = 0;

        for (Document document : page) {
            if (!seenInPage.add(document.dedupKey().value())) {
                duplicates++;
                continue;
            }
            Optional<UUID> existing = documents
                    .findIdByExternalRef(document.externalRef(), document.publishedOn())
                    .or(() -> documents.findIdByDedupKey(document.dedupKey(), document.publishedOn()));
            if (existing.isPresent()) {
                duplicates++;
                documentIds.add(existing.get());
                continue;
            }
            UUID id = documents.save(document);
            documentIds.add(id);
            events.add(DocumentIngested.of(document, now));
            // Тег — источник: «корпус растёт» без разбивки не отвечает на вопрос, который задают в
            // инциденте, — какой именно источник перестал отдавать новое. Считаются только
            // созданные, не дубликаты: счётчик с повторами растёт и тогда, когда нового нет.
            meterRegistry
                    .counter("horizon.documents.ingested", "source", document.sourceId())
                    .increment();
            created++;
        }

        // One outbox write per page keeps the event stream and the documents atomically consistent.
        publisher.publish(events);
        if (log.isDebugEnabled()) {
            log.debug("Persisted page: {} created, {} duplicates", created, duplicates);
        }
        return new BatchResult(created, duplicates, documentIds);
    }

    /** Outcome of one page write. */
    public record BatchResult(int created, int duplicates, List<UUID> documentIds) {

        public static final BatchResult EMPTY = new BatchResult(0, 0, List.of());

        public BatchResult {
            documentIds = List.copyOf(documentIds);
        }

        public Set<UUID> distinctIds() {
            return new HashSet<>(documentIds);
        }
    }
}
