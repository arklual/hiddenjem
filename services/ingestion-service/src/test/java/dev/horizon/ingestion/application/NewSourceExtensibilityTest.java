package dev.horizon.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentStream;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.RateLimiter;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.source.Source;

/**
 * Executable proof of the extension point `docs/02-architecture/05-extensibility.md` row 1 claims.
 *
 * <p>The document tells whoever receives the final requirements that a new data source costs "one
 * connector plus a row". A claim like that is worth nothing unless something fails when it stops
 * being true, so this test adds a source the production code has never heard of and asserts the
 * catalogue routes to it.
 *
 * <p>It also pins the part the document used to leave out: the connector alone is not enough. A
 * {@link Source} row must exist, and there is no endpoint that creates one — so a new source ships
 * with a migration, and forgetting it produces a connector that is deployed and silently never
 * called.
 */
class NewSourceExtensibilityTest {

    private static final String NEW_SOURCE_ID = "elibrary";

    private static CollectionRequest request(Set<SourceClass> classes) {
        return new CollectionRequest(
                UUID.randomUUID(),
                "квантовые вычисления",
                "квантовые вычисления",
                "ru",
                LocalDate.of(2018, 1, 1),
                LocalDate.of(2025, 12, 31),
                classes,
                100,
                List.of());
    }

    private static Source enabledRow(String id) {
        return Source.of(
                id, "eLibrary", SourceClass.JOURNAL_ARTICLE, true, "https://elibrary.ru", 60, false, Map.of(), 1.0, 1);
    }

    private ConnectorCatalog catalogOf(List<Source> rows, NormalizingSourceConnector... connectors) {
        return new ConnectorCatalog(List.of(connectors), new StubSources(rows), new NoopRateLimiters());
    }

    @Test
    void aConnectorTheApplicationHasNeverHeardOfIsPickedUpAndRoutedTo() {
        var connector = new FakeConnector(NEW_SOURCE_ID, Set.of(SourceClass.JOURNAL_ARTICLE));

        var catalog = catalogOf(List.of(enabledRow(NEW_SOURCE_ID)), connector);

        assertThat(catalog.byId(NEW_SOURCE_ID)).containsSame(connector);
        assertThat(catalog.connectorsFor(request(Set.of(SourceClass.JOURNAL_ARTICLE))))
                .containsExactly(connector);
    }

    @Test
    void aConnectorWithoutItsSourceRowIsNeverCalled() {
        // The failure mode the document omitted. Nothing throws; the connector is simply absent from
        // every collection, which reads as "the source returns nothing" rather than as a missing row.
        var connector = new FakeConnector(NEW_SOURCE_ID, Set.of(SourceClass.JOURNAL_ARTICLE));

        var catalog = catalogOf(List.of(), connector);

        assertThat(catalog.byId(NEW_SOURCE_ID)).containsSame(connector);
        assertThat(catalog.connectorsFor(request(Set.of(SourceClass.JOURNAL_ARTICLE))))
                .isEmpty();
    }

    @Test
    void aDisabledSourceIsSkippedWithoutTouchingItsConnector() {
        var connector = new FakeConnector(NEW_SOURCE_ID, Set.of(SourceClass.JOURNAL_ARTICLE));
        var disabled = enabledRow(NEW_SOURCE_ID);
        disabled.disable();

        var catalog = catalogOf(List.of(disabled), connector);

        assertThat(catalog.connectorsFor(request(Set.of(SourceClass.JOURNAL_ARTICLE))))
                .isEmpty();
    }

    @Test
    void aSourceProvidingOtherClassesIsNotAnAvailabilityProblem() {
        // Asking for patents must not drag in a paper source, and must not report it as unavailable
        // either — it was never a candidate for this request.
        var connector = new FakeConnector(NEW_SOURCE_ID, Set.of(SourceClass.JOURNAL_ARTICLE));

        var catalog = catalogOf(List.of(enabledRow(NEW_SOURCE_ID)), connector);

        assertThat(catalog.connectorsFor(request(Set.of(SourceClass.PATENT)))).isEmpty();
    }

    @Test
    void connectorsAreOrderedByIdSoACollectionCannotDependOnBeanOrder() {
        // Determinism (ADR-0015) reaches the ingestion layer too: Spring's injection order is not a
        // contract, and a corpus that depends on it is not reproducible.
        var second = new FakeConnector("zenodo", Set.of(SourceClass.JOURNAL_ARTICLE));
        var first = new FakeConnector("arxiv", Set.of(SourceClass.JOURNAL_ARTICLE));

        var catalog = catalogOf(List.of(enabledRow("zenodo"), enabledRow("arxiv")), second, first);

        assertThat(catalog.all()).extracting(c -> c.descriptor().id()).containsExactly("arxiv", "zenodo");
    }

    // ───────────────────────────── stubs ─────────────────────────────

    /** The whole of what a new source has to implement, as far as the catalogue is concerned. */
    private record FakeConnector(String id, Set<SourceClass> classes) implements NormalizingSourceConnector {

        @Override
        public SourceDescriptor descriptor() {
            return new SourceDescriptor(id, id, classes.iterator().next(), classes, 60, false, true, null, false);
        }

        @Override
        public boolean supports(CollectionRequest request) {
            return true;
        }

        @Override
        public java.util.stream.Stream<dev.horizon.ingestion.domain.port.RawDocument> fetch(
                CollectionRequest request, Cursor cursor) {
            return java.util.stream.Stream.empty();
        }

        @Override
        public DocumentStream collect(CollectionRequest request, Cursor cursor) {
            return DocumentStream.empty(cursor);
        }
    }

    private record StubSources(List<Source> rows) implements SourceRepository {

        @Override
        public List<Source> findAll() {
            return rows;
        }

        @Override
        public Optional<Source> findById(String id) {
            return rows.stream().filter(source -> source.id().equals(id)).findFirst();
        }

        @Override
        public Source save(Source source) {
            return source;
        }
    }

    private static final class NoopRateLimiters implements RateLimiters {

        @Override
        public RateLimiter forSource(String sourceId, int permitsPerMinute) {
            return new RateLimiter() {
                @Override
                public void acquire() {}

                @Override
                public boolean tryAcquire() {
                    return true;
                }

                @Override
                public int permitsPerMinute() {
                    return permitsPerMinute;
                }
            };
        }

        @Override
        public void reconfigure(String sourceId, int permitsPerMinute) {}
    }
}
