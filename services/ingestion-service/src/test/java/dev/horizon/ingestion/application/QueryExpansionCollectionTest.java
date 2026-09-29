package dev.horizon.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.horizon.ingestion.config.QueryExpansionProperties;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.AnalysisMode;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentStream;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.QueryExpander;
import dev.horizon.ingestion.domain.port.RawDocument;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.run.RunCounters;
import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;
import dev.horizon.platform.common.id.Uuid7;

/**
 * Узкие запросы расширения уходят в источники отдельно от запроса направления и в свой резерв.
 *
 * <p>Зачем шаг: сбор спрашивал источники словами направления и получал его центр, а слабый сигнал
 * живёт на краю поля. У шестидесяти технологий размеченного датасета из ста в собранном корпусе не
 * нашлось даже их слов в одном документе (разбор 90).
 */
class QueryExpansionCollectionTest {

    private static final int BUDGET = 1000;
    private static final QueryExpansionProperties ON = new QueryExpansionProperties(
            true, "http://nlp", 12, 0.4, 0, 40, Duration.ofSeconds(5), Duration.ofMinutes(5), null, null);

    private static final QueryExpander TWO_QUERIES = (query, targets, limit, languages) -> new QueryExpander.Expansion(
            List.of(
                    new QueryExpander.ExpandedQuery("cloudlet computing", "core"),
                    new QueryExpander.ExpandedQuery("vehicular fog computing", "edge")),
            "gpt-5.6-luna");

    @Test
    void узкиеЗапросыСпрашиваютсяВКаждомИсточникеСвоейФормулировкой() {
        var run = collect(TWO_QUERIES, ON, List.of("arxiv", "openalex"), Set.of());

        assertThat(run.phrasesAsked("arxiv")).contains("cloudlet computing", "vehicular fog computing");
        assertThat(run.phrasesAsked("openalex")).contains("cloudlet computing", "vehicular fog computing");
        // Узкий запрос — английская формулировка как есть, без кодов направления: иначе источник
        // с предметными кодами спросил бы снова центр поля.
        assertThat(run.requests)
                .filteredOn(r -> r.query().equals("cloudlet computing"))
                .allSatisfy(r -> {
                    assertThat(r.language()).isEqualTo("en");
                    assertThat(r.subjectTargets()).isEmpty();
                    assertThat(r.maxDocuments()).isLessThanOrEqualTo(40);
                });
    }

    @Test
    void запросНаправленияОтдаётРезервУзкимЗапросам() {
        var run = collect(TWO_QUERIES, ON, List.of("arxiv", "openalex"), Set.of());

        int base = run.requests.stream()
                .filter(r -> r.query().equals("периферийные вычисления"))
                .mapToInt(CollectionRequest::maxDocuments)
                .sum();
        assertThat(base).isEqualTo(BUDGET - 400);
        assertThat(run.requests.stream()
                        .mapToInt(CollectionRequest::maxDocuments)
                        .sum())
                .isLessThanOrEqualTo(BUDGET);
    }

    @Test
    void безРасширенияСборИдётКакПрежде() {
        var run = collect(QueryExpander.NONE, ON, List.of("arxiv", "openalex"), Set.of());

        assertThat(run.requests).extracting(CollectionRequest::query).containsOnly("периферийные вычисления");
        // Модель не дала формулировок — резерв под них не пропадает: источники, выбравшие свою долю
        // целиком, дозапрашиваются вторым кругом, и корпус набирает весь бюджет.
        var snapshot = ArgumentCaptor.forClass(CorpusSnapshot.class);
        verify(run.publisher).publishCollected(anyString(), any(), anyInt(), snapshot.capture());
        assertThat(snapshot.getValue().documentCount()).isEqualTo(BUDGET);
    }

    @Test
    void выключенноеРасширениеМодельНеСпрашивает() {
        QueryExpander mustNotBeCalled = (query, targets, limit, languages) -> {
            throw new AssertionError("расширение выключено, а модель спросили");
        };
        var run = collect(mustNotBeCalled, QueryExpansionProperties.disabled(), List.of("arxiv"), Set.of());

        assertThat(run.requests).extracting(CollectionRequest::query).containsOnly("периферийные вычисления");
    }

    @Test
    void лентыИОтказавшиеИсточникиУзкимиЗапросамиНеСпрашиваются() {
        // RSS не ищет на стороне сервера — каждая формулировка скачала бы те же ленты; отказавший на
        // запросе направления откажет и на узких, потратив таймаут на каждую формулировку.
        var run = collect(TWO_QUERIES, ON, List.of("arxiv", "rss", "github"), Set.of("github"));

        assertThat(run.phrasesAsked("arxiv")).contains("cloudlet computing");
        assertThat(run.phrasesAsked("rss")).containsOnly("периферийные вычисления");
        assertThat(run.phrasesAsked("github")).containsOnly("периферийные вычисления");
    }

    @Test
    void документыУзкихЗапросовВходятВСнимокАИсточникНеСтановитсяНедоступным() {
        var run = collect(TWO_QUERIES, ON, List.of("arxiv"), Set.of());

        ArgumentCaptor<CorpusSnapshot> snapshot = ArgumentCaptor.forClass(CorpusSnapshot.class);
        verify(run.publisher).publishCollected(anyString(), any(), eq(1), snapshot.capture());
        // Запрос направления отдал свои 600, каждый из двух узких — свою порцию.
        assertThat(snapshot.getValue().documentCount()).isEqualTo(600 + 2 * 40);
    }

    @Test
    void узкиеЗапросыНеЖдутГлубокогоИсследования() {
        // Исследование отвечает, только когда узкий запрос уже ушёл в arXiv. Если бы вторая фаза
        // ждала исследование, узкий запрос не ушёл бы никогда, и исследование ответило бы по
        // таймауту — с флагом «не дождались».
        var narrowAsked = new CountDownLatch(1);
        var researchSawNarrow = new AtomicBoolean(false);
        var run = collect(
                TWO_QUERIES, ON, List.of("arxiv", "deepresearch"), Set.of(), 1, AnalysisMode.QUALITY,
                (source, request) -> {
                    if (source.equals("deepresearch")) {
                        researchSawNarrow.set(narrowAsked.await(10, TimeUnit.SECONDS));
                    } else if (request.subjectTargets().isEmpty()) {
                        narrowAsked.countDown();
                    }
                });

        assertThat(researchSawNarrow).as("узкий запрос ушёл, пока исследование ещё шло").isTrue();
        assertThat(run.phrasesAsked("deepresearch")).containsOnly("периферийные вычисления");
        assertThat(run.phrasesAsked("arxiv")).contains("cloudlet computing", "vehicular fog computing");
        // Документы исследования — в снимке, хотя вторая фаза его не ждала. Доля исследования
        // (300) считается израсходованной: arXiv — 300, узкие — по 40, исследование — 300.
        var snapshot = ArgumentCaptor.forClass(CorpusSnapshot.class);
        verify(run.publisher).publishCollected(anyString(), any(), anyInt(), snapshot.capture());
        assertThat(snapshot.getValue().documentCount()).isEqualTo(300 + 2 * 40 + 300);
        assertThat(snapshot.getValue().sourcesUsed()).containsExactly("arxiv", "deepresearch");
    }

    @Test
    void режимАнализаДоходитДоКаждогоЗапросаКИсточнику() {
        // Бюджет глубокого исследования коннектор выбирает по режиму в запросе — значит, режим
        // обязан дойти до запроса, и направления, и узкого.
        var run = collect(
                TWO_QUERIES, ON, List.of("arxiv", "deepresearch"), Set.of(), 1, AnalysisMode.QUALITY,
                (source, request) -> {});

        assertThat(run.requests).isNotEmpty().allSatisfy(r -> assertThat(r.mode()).isEqualTo(AnalysisMode.QUALITY));
        assertThat(collect(TWO_QUERIES, ON, List.of("arxiv"), Set.of()).requests)
                .allSatisfy(r -> assertThat(r.mode()).isEqualTo(AnalysisMode.FAST));
    }

    @Test
    void группыЧередуютсяЧтобыСтыкиНеОставалисьВХвосте() {
        var ordered = CollectDomainCorpusUseCase.interleaveGroups(List.of(
                new QueryExpander.ExpandedQuery("c1", "core"),
                new QueryExpander.ExpandedQuery("c2", "core"),
                new QueryExpander.ExpandedQuery("n1", "emerging"),
                new QueryExpander.ExpandedQuery("e1", "edge"),
                new QueryExpander.ExpandedQuery("e2", "edge")));

        assertThat(ordered)
                .extracting(QueryExpander.ExpandedQuery::query)
                .containsExactly("c1", "n1", "e1", "c2", "e2");
    }

    @Test
    void перерасходИсточникаНеОтнимаетРезервУДругихФормулировок() {
        // Источник отдаёт страницу целиком — вчетверо больше порции. Все формулировки всё равно
        // спрошены: лишнее остаётся в корпусе, но с резерва не списывается.
        QueryExpander twelve = (query, targets, limit, languages) -> new QueryExpander.Expansion(
                IntStream.range(0, 12)
                        .mapToObj(i -> new QueryExpander.ExpandedQuery("phrase " + i, "core"))
                        .toList(),
                "gpt-5.6-luna");
        var run = collect(twelve, ON, List.of("arxiv"), Set.of(), 4);

        assertThat(run.phrasesAsked("arxiv")).hasSize(13);
    }

    @Test
    void абсолютныйРезервИдётСверхБюджетаНаправленияИДаётГлубину() {
        // Резерв 1000 поверх бюджета: запрос направления получает свои 600, как при доле, а пара
        // «формулировка × источник» — по восемьдесят, а не по сорок.
        var deep = new QueryExpansionProperties(
                true, "http://nlp", 12, 0.4, 1000, 80, Duration.ofSeconds(5), Duration.ofMinutes(5), null, null);
        var run = collect(TWO_QUERIES, deep, List.of("arxiv", "openalex"), Set.of());

        int base = run.requests.stream()
                .filter(r -> r.query().equals("периферийные вычисления"))
                .mapToInt(CollectionRequest::maxDocuments)
                .sum();
        assertThat(base).isEqualTo(BUDGET - 400);
        assertThat(run.requests)
                .filteredOn(r -> r.subjectTargets().isEmpty())
                .extracting(CollectionRequest::maxDocuments)
                .containsOnly(80);
    }

    @Test
    void русскиеИКитайскиеФормулировкиУходятТолькоВИсточникиЭтогоЯзыка() {
        QueryExpander trilingual = (query, targets, limit, languages) -> new QueryExpander.Expansion(
                List.of(
                        new QueryExpander.ExpandedQuery("federated learning orchestration", "edge", "en"),
                        new QueryExpander.ExpandedQuery("оркестрация федеративного обучения", "edge", "ru"),
                        new QueryExpander.ExpandedQuery("联邦学习编排", "edge", "zh")),
                "gpt-5.6-luna");
        var run = collect(trilingual, ON, List.of("arxiv", "openalex", "crossref"), Set.of());

        assertThat(run.phrasesAsked("arxiv")).doesNotContain("оркестрация федеративного обучения", "联邦学习编排");
        assertThat(run.phrasesAsked("openalex")).contains("оркестрация федеративного обучения", "联邦学习编排");
        // Crossref режет русский и китайский запрос на слова и иероглифы — отвечает шумом.
        assertThat(run.phrasesAsked("crossref")).doesNotContain("оркестрация федеративного обучения", "联邦学习编排");
        assertThat(run.requests).filteredOn(r -> r.query().equals("联邦学习编排")).allSatisfy(r -> assertThat(r.language())
                .isEqualTo("zh"));
    }

    @Test
    void языкиПоУмолчаниюНачинаютсяСАнглийского() {
        var properties = new QueryExpansionProperties(
                true, "http://nlp", 12, 0.4, 0, 40, null, null, List.of("zh", "RU", "de"), null);

        assertThat(properties.languages()).containsExactly("en", "zh", "ru");
        assertThat(properties.asks("github", "ru")).isFalse();
        assertThat(properties.asks("github", "en")).isTrue();
        assertThat(properties.asks("openalex", "zh")).isTrue();
    }

    private record Run(List<CollectionRequest> requests, List<String> sources, CorpusResultPublisher publisher) {

        List<String> phrasesAsked(String source) {
            List<String> phrases = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                if (sources.get(i).equals(source)) {
                    phrases.add(requests.get(i).query());
                }
            }
            return phrases;
        }
    }

    /** Каждый источник отдаёт столько новых документов, сколько попросили; отказавшие — ничего. */
    private static Run collect(
            QueryExpander expander, QueryExpansionProperties properties, List<String> ids, Set<String> failing) {
        return collect(expander, properties, ids, failing, 1);
    }

    /** {@code overshoot} — во сколько раз источник переотдаёт на узкий запрос сверх порции. */
    private static Run collect(
            QueryExpander expander,
            QueryExpansionProperties properties,
            List<String> ids,
            Set<String> failing,
            int overshoot) {
        return collect(expander, properties, ids, failing, overshoot, AnalysisMode.FAST, (source, request) -> {});
    }

    /** Что источник делает, прежде чем ответить: здесь тест держит глубокое исследование. */
    @FunctionalInterface
    private interface BeforeAnswer {
        void accept(String source, CollectionRequest request) throws InterruptedException;
    }

    private static Run collect(
            QueryExpander expander,
            QueryExpansionProperties properties,
            List<String> ids,
            Set<String> failing,
            int overshoot,
            AnalysisMode mode,
            BeforeAnswer beforeAnswer) {
        var connectors = new ArrayList<NormalizingSourceConnector>();
        ids.forEach(id -> connectors.add(new FakeConnector(id)));
        var catalog = mock(ConnectorCatalog.class);
        when(catalog.connectorsFor(any())).thenReturn(connectors);

        var requests = new ArrayList<CollectionRequest>();
        var sources = new ArrayList<String>();
        var collection = mock(ConnectorCollectionService.class);
        when(collection.startRun(any(), any(), any())).thenReturn(null);
        when(collection.execute(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            NormalizingSourceConnector connector = invocation.getArgument(0);
            CollectionRequest request = invocation.getArgument(1);
            String sourceId = connector.descriptor().id();
            // Источники идут параллельно: запрос и источник записываются вместе, иначе индексы
            // двух списков разъехались бы.
            synchronized (requests) {
                requests.add(request);
                sources.add(sourceId);
            }
            beforeAnswer.accept(sourceId, request);
            if (failing.contains(sourceId)) {
                return CollectionOutcome.failed(
                        sourceId, UUID.randomUUID(), List.of(), RunCounters.ZERO, "UPSTREAM_UNAVAILABLE", "недоступен");
            }
            List<UUID> docs = new ArrayList<>();
            int yield =
                    request.subjectTargets().isEmpty() ? request.maxDocuments() * overshoot : request.maxDocuments();
            for (int i = 0; i < yield; i++) {
                docs.add(UUID.randomUUID());
            }
            return CollectionOutcome.succeeded(sourceId, UUID.randomUUID(), docs, RunCounters.ZERO);
        });

        var publisher = mock(CorpusResultPublisher.class);
        when(publisher.alreadyPublished(anyString())).thenReturn(false);

        new CollectDomainCorpusUseCase(
                        catalog, collection, publisher, new Uuid7(Clock.systemUTC()), expander, properties)
                .handle(new CollectDomainCorpusCommand(
                        UUID.randomUUID(),
                        1,
                        "периферийные вычисления",
                        "периферийные вычисления",
                        "ru",
                        LocalDate.of(2023, 1, 1),
                        LocalDate.of(2026, 9, 1),
                        Set.of(),
                        BUDGET,
                        List.of("edge computing"),
                        mode));
        return new Run(requests, sources, publisher);
    }

    private record FakeConnector(String id) implements NormalizingSourceConnector {

        @Override
        public SourceDescriptor descriptor() {
            return new SourceDescriptor(
                    id,
                    id,
                    SourceClass.JOURNAL_ARTICLE,
                    Set.of(SourceClass.JOURNAL_ARTICLE),
                    60,
                    false,
                    true,
                    null,
                    false);
        }

        @Override
        public boolean supports(CollectionRequest request) {
            return true;
        }

        @Override
        public DocumentStream collect(CollectionRequest request, Cursor cursor) {
            throw new UnsupportedOperationException("сбор в этом тесте подменён");
        }

        @Override
        public Stream<RawDocument> fetch(CollectionRequest request, Cursor cursor) {
            throw new UnsupportedOperationException("сбор в этом тесте подменён");
        }
    }
}
