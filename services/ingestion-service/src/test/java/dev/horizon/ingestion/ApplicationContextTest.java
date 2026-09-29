package dev.horizon.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import dev.horizon.ingestion.application.ConnectorCatalog;
import dev.horizon.ingestion.domain.port.CorpusSnapshotRepository;
import dev.horizon.ingestion.domain.port.DistributedLock;
import dev.horizon.ingestion.domain.port.DocumentRepository;
import dev.horizon.ingestion.domain.port.IdempotencyGuard;
import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.SourceCursorRepository;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.platform.common.event.DomainEventPublisher;

/**
 * Proves the Spring context actually starts and every port has an implementation behind it.
 *
 * <p>This is the cheapest high-value test in the module. Without it, a driven adapter that was
 * written but never declared as a bean is invisible until deployment: the service fails to start,
 * the {@code CollectDomainCorpus} command is never consumed, and every research request dies of a
 * ten-minute saga timeout with no indication of why. Unit tests cannot see that class of defect at
 * all, because each class is perfectly correct in isolation.
 *
 * <p>External infrastructure is deliberately not required. The context is started against an
 * in-memory database with listeners stopped: the question being asked is "is everything wired?",
 * not "does the database work" — that is what the Testcontainers integration tests are for.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:ingestion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.default_schema=",
            "spring.flyway.enabled=false",
            "spring.kafka.listener.auto-startup=false",
            "horizon.outbox.enabled=false",
            "horizon.ingestion.schedule-enabled=false",
        })
class ApplicationContextTest {

    @Autowired
    private ConnectorCatalog catalog;

    @Autowired
    private List<NormalizingSourceConnector> connectors;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private IngestionRunRepository runs;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private SourceCursorRepository cursors;

    @Autowired
    private CorpusSnapshotRepository snapshots;

    @Autowired
    private IdempotencyGuard idempotency;

    @Autowired
    private DistributedLock lock;

    @Autowired
    private RateLimiters rateLimiters;

    @Autowired
    private DomainEventPublisher events;

    @Test
    @DisplayName("the context starts and every outbound port has exactly one implementation")
    void everyPortIsSatisfied() {
        assertThat(documents).isNotNull();
        assertThat(runs).isNotNull();
        assertThat(sources).isNotNull();
        assertThat(cursors).isNotNull();
        assertThat(snapshots).isNotNull();
        assertThat(idempotency).isNotNull();
        assertThat(lock).isNotNull();
        assertThat(rateLimiters).isNotNull();
        assertThat(events).isNotNull();
    }

    @Test
    @DisplayName("all twenty-five documented connectors are registered")
    void allConnectorsAreRegistered() {
        // BR-C1 requires at least four classes of open source. A connector that exists as a class
        // but is not a bean satisfies no requirement at all, so the count is asserted, not the
        // presence of the files.
        assertThat(connectors).hasSize(25);
        assertThat(catalog.all())
                .extracting(connector -> connector.descriptor().id())
                .containsExactlyInAnyOrder(
                        "arxiv",
                        "alphaxiv",
                        "openalex",
                        "crossref",
                        "uspto",
                        "github",
                        "rss",
                        // Источники из исследований к кейсу (разбор 89).
                        "semanticscholar",
                        "europepmc",
                        "hackernews",
                        "gdelt",
                        // Продуктовые и отраслевые источники (разбор 101).
                        "habr",
                        "industry",
                        // Глубокое исследование на Luna (разбор 102).
                        "deepresearch",
                        // Ранняя стадия и финтех (разбор 109): гранты на прототипы, программы регуляторов.
                        "sbir",
                        "nsf",
                        "regulators",
                        // Деньги и стандарты (разбор 109): раунды и IPO, черновики стандартов.
                        "edgar",
                        "ietf",
                        // Рыночная стадия (разбор 109): пресс-релизы и запуски продуктов.
                        "globenewswire",
                        "prnewswire",
                        "producthunt",
                        // Веб-корпус по направлению: страницы-доказательства сигналов.
                        "webcorpus",
                        // The Lens: научные работы и патенты, откуда выгружен эталонный набор кейса.
                        "lens",
                        "lenspatents");
    }

    @Test
    @DisplayName("each connector declares a distinct id and a source class")
    void connectorDescriptorsAreWellFormed() {
        var descriptors = catalog.all().stream()
                .map(NormalizingSourceConnector::descriptor)
                .toList();

        assertThat(descriptors).extracting(SourceDescriptor::id).doesNotHaveDuplicates();
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.id()).isNotBlank();
            assertThat(descriptor.primaryClass()).isNotNull();
        });
    }

    @Test
    @DisplayName("the catalogue returns connectors in a stable order")
    void catalogueOrderIsDeterministic() {
        // Collection order affects which source contributes a document first and therefore which
        // provenance is recorded; a bean-registration-order dependency would make runs differ
        // between restarts for no visible reason (ADR-0015).
        var first = catalog.all().stream().map(c -> c.descriptor().id()).toList();
        var second = catalog.all().stream().map(c -> c.descriptor().id()).toList();

        assertThat(second).isEqualTo(first);
        assertThat(first).isSorted();
    }
}
