package dev.horizon.ingestion.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.id.Uuid7;

/**
 * A new canonical document entered the corpus (FR-04.8).
 *
 * <p>Published to {@code horizon.documents.v1} through the transactional outbox, keyed by document
 * id so that all events about one document stay ordered.
 *
 * <p>{@link Payload} mirrors {@code contracts/schemas/document-ingested.event.json} field for field
 * — that schema declares {@code additionalProperties: false}, so an extra field here would break
 * every consumer. The internal {@link Document} may evolve freely behind it; this record is the
 * published language.
 */
public record DocumentIngested(UUID eventId, Instant occurredAt, Document document) implements DomainEvent {

    public static DocumentIngested of(Document document, Instant occurredAt) {
        return new DocumentIngested(Uuid7.randomUuid7(), occurredAt, document);
    }

    @Override
    public String aggregateType() {
        return "Document";
    }

    @Override
    public String aggregateId() {
        return document.id().toString();
    }

    @Override
    public String eventType() {
        return IngestionTopics.TYPE_DOCUMENT_INGESTED;
    }

    @Override
    public String topic() {
        return IngestionTopics.DOCUMENTS;
    }

    @Override
    public String partitionKey() {
        return document.id().toString();
    }

    @Override
    public Object payload() {
        List<AuthorPayload> authors = new ArrayList<>(document.authors().size());
        for (Author author : document.authors()) {
            authors.add(new AuthorPayload(
                    author.fullName(),
                    author.orcid(),
                    author.organizationName(),
                    author.organizationType() == null
                            ? null
                            : author.organizationType().name(),
                    author.organizationCountry()));
        }
        List<TopicPayload> topics = new ArrayList<>(document.topics().size());
        for (DocumentTopic topic : document.topics()) {
            topics.add(new TopicPayload(topic.code(), topic.label(), topic.score()));
        }
        var venue = document.venue() == null
                ? null
                : new VenuePayload(
                        document.venue().name(),
                        document.venue().type(),
                        document.venue().issn());
        return new Payload(
                document.id(),
                document.sourceId(),
                document.sourceClass(),
                document.externalId(),
                document.title(),
                document.abstractText(),
                document.language(),
                document.publishedOn(),
                document.identifiers().doi(),
                document.identifiers().arxivId(),
                document.identifiers().patentNumber(),
                document.identifiers().url(),
                venue,
                authors,
                topics,
                document.metrics().citationCount(),
                document.extraMetrics(),
                document.dedupKey().value(),
                document.provenance().fetchedAt());
    }

    /** Wire shape — see {@code document-ingested.event.json}. */
    public record Payload(
            UUID documentId,
            String sourceId,
            SourceClass sourceClass,
            String externalId,
            String title,
            String abstractText,
            String language,
            LocalDate publishedOn,
            String doi,
            String arxivId,
            String patentNumber,
            String url,
            VenuePayload venue,
            List<AuthorPayload> authors,
            List<TopicPayload> topics,
            Integer citationCount,
            Map<String, Number> extraMetrics,
            String dedupKey,
            Instant fetchedAt) {}

    public record VenuePayload(String name, String type, String issn) {}

    public record AuthorPayload(
            String fullName,
            String orcid,
            String organizationName,
            String organizationType,
            String organizationCountry) {}

    public record TopicPayload(String code, String label, Double score) {}
}
