package dev.horizon.ingestion.domain.document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;

/**
 * Canonical record of a publication, patent, repository or news item — the aggregate root of the
 * Source Ingestion context (domain model §5).
 *
 * <p>This is the <em>only</em> document shape that leaves the context: every connector's foreign
 * model dies inside its normalizer (the anti-corruption layer). Downstream contexts therefore never
 * learn that OpenAlex inverts abstracts or that USPTO calls a title {@code inventionTitle}.
 *
 * <p>Immutable by construction. The one legitimate change over time is metric enrichment (citations
 * grow), modelled as {@link #enrichWith(DocumentMetrics)} returning a new instance with an
 * incremented {@link #version()} for optimistic locking.
 */
public record Document(
        UUID id,
        ExternalRef externalRef,
        SourceClass sourceClass,
        String title,
        String abstractText,
        String language,
        LocalDate publishedOn,
        DocumentIdentifiers identifiers,
        List<Author> authors,
        Venue venue,
        List<DocumentTopic> topics,
        DocumentMetrics metrics,
        Provenance provenance,
        DedupKey dedupKey,
        int version) {

    public Document {
        Guards.requireNonNull(id, "document.id");
        Guards.requireNonNull(externalRef, "document.externalRef");
        Guards.requireNonNull(sourceClass, "document.sourceClass");
        title = Guards.requireText(title, "document.title").trim();
        abstractText = blankToNull(abstractText);
        language = normalizeLanguage(language);
        Guards.requireNonNull(publishedOn, "document.publishedOn");
        Guards.requireNonNull(identifiers, "document.identifiers");
        Guards.requireNonNull(provenance, "document.provenance");
        Guards.requireNonNull(dedupKey, "document.dedupKey");
        Guards.requireArgument(
                provenance.sourceId().equals(externalRef.sourceId()),
                "provenance.sourceId must match externalRef.sourceId");
        authors = authors == null ? List.of() : List.copyOf(authors);
        topics = topics == null ? List.of() : List.copyOf(dedupeTopics(topics));
        metrics = metrics == null ? DocumentMetrics.EMPTY : metrics;
        Guards.requireArgument(version >= 0, "version must not be negative");
    }

    /** Returns a copy carrying fresh metrics; the previous version number is bumped. */
    public Document enrichWith(DocumentMetrics newMetrics) {
        Guards.requireNonNull(newMetrics, "metrics");
        return new Document(
                id,
                externalRef,
                sourceClass,
                title,
                abstractText,
                language,
                publishedOn,
                identifiers,
                authors,
                venue,
                topics,
                newMetrics,
                provenance,
                dedupKey,
                version + 1);
    }

    public String sourceId() {
        return externalRef.sourceId();
    }

    public String externalId() {
        return externalRef.externalId();
    }

    public int publicationYear() {
        return publishedOn.getYear();
    }

    public Optional<Venue> venueOrEmpty() {
        return Optional.ofNullable(venue);
    }

    public Map<String, Number> extraMetrics() {
        return metrics.asExtraMetrics();
    }

    public List<String> authorNames() {
        List<String> names = new ArrayList<>(authors.size());
        for (Author author : authors) {
            names.add(author.fullName());
        }
        return Collections.unmodifiableList(names);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<DocumentTopic> dedupeTopics(List<DocumentTopic> topics) {
        // Sources occasionally repeat a concept at two levels of their hierarchy; the primary key of
        // document_topics_raw is (document_id, code), so duplicates would blow up the insert.
        Map<String, DocumentTopic> byCode = new LinkedHashMap<>();
        for (DocumentTopic topic : topics) {
            byCode.putIfAbsent(topic.code(), topic);
        }
        return new ArrayList<>(byCode.values());
    }

    /** ISO 639-1, lower case — the column is {@code char(2)}. */
    private static String normalizeLanguage(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String language = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (language.length() > 2) {
            language = language.substring(0, 2);
        }
        return language.length() == 2 ? language : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Fluent construction for normalizers.
     *
     * <p>The builder — not the caller — computes the {@link DedupKey}, so no connector can invent its
     * own notion of document identity (FR-04.3 lives in exactly one place).
     */
    public static final class Builder {

        private UUID id;
        private String sourceId;
        private String externalId;
        private SourceClass sourceClass;
        private String title;
        private String abstractText;
        private String language;
        private LocalDate publishedOn;
        private String doi;
        private String arxivId;
        private String patentNumber;
        private String url;
        private final List<Author> authors = new ArrayList<>();
        private Venue venue;
        private final List<DocumentTopic> topics = new ArrayList<>();
        private DocumentMetrics metrics = DocumentMetrics.EMPTY;
        private Provenance provenance;

        private Builder() {}

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder externalRef(String source, String external) {
            this.sourceId = source;
            this.externalId = external;
            return this;
        }

        public Builder sourceClass(SourceClass value) {
            this.sourceClass = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder abstractText(String value) {
            this.abstractText = value;
            return this;
        }

        public Builder language(String value) {
            this.language = value;
            return this;
        }

        public Builder publishedOn(LocalDate value) {
            this.publishedOn = value;
            return this;
        }

        public Builder doi(String value) {
            this.doi = value;
            return this;
        }

        public Builder arxivId(String value) {
            this.arxivId = value;
            return this;
        }

        public Builder patentNumber(String value) {
            this.patentNumber = value;
            return this;
        }

        public Builder url(String value) {
            this.url = value;
            return this;
        }

        public Builder author(Author value) {
            if (value != null) {
                this.authors.add(value);
            }
            return this;
        }

        public Builder authors(List<Author> values) {
            if (values != null) {
                this.authors.addAll(values);
            }
            return this;
        }

        public Builder venue(Venue value) {
            this.venue = value;
            return this;
        }

        public Builder topic(DocumentTopic value) {
            if (value != null) {
                this.topics.add(value);
            }
            return this;
        }

        public Builder topics(List<DocumentTopic> values) {
            if (values != null) {
                values.forEach(this::topic);
            }
            return this;
        }

        public Builder metrics(DocumentMetrics value) {
            this.metrics = value == null ? DocumentMetrics.EMPTY : value;
            return this;
        }

        public Builder provenance(Provenance value) {
            this.provenance = value;
            return this;
        }

        public Document build() {
            var identifiers = new DocumentIdentifiers(doi, arxivId, patentNumber, url);
            var ref = new ExternalRef(sourceId, externalId);
            Guards.requireNonNull(publishedOn, "document.publishedOn");
            var names = new ArrayList<String>(authors.size());
            for (Author author : authors) {
                names.add(author.fullName());
            }
            var key = DedupKey.compute(identifiers, title, names, publishedOn.getYear());
            return new Document(
                    id == null ? Uuid7.randomUuid7() : id,
                    ref,
                    sourceClass,
                    title,
                    abstractText,
                    language,
                    publishedOn,
                    identifiers,
                    authors,
                    venue,
                    topics,
                    metrics,
                    provenance,
                    key,
                    0);
        }
    }
}
