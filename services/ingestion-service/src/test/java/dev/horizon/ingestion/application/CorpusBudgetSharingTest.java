package dev.horizon.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentStream;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.run.RunCounters;
import dev.horizon.platform.common.id.Uuid7;

/**
 * Бюджет корпуса делится между источниками, а не достаётся первому же.
 *
 * <p>Отсутствие этого свойства нельзя было увидеть ни в одной ошибке. На стенде по «квантовым
 * вычислениям» crossref приносил 2893 документа, arxiv — ещё 2100, и весь бюджет в пять тысяч
 * заканчивался на втором источнике: github, openalex и rss не спрашивали вовсе, а в отчёте они
 * значились недоступными. То есть оговорка «корпус собран не полностью» появлялась при полностью
 * живых источниках, и списывалась она на их недоступность.
 *
 * <p>Цена ошибки не в оговорке. Методика строит выводы на разнородности корпуса — статьи, патенты,
 * код, новости, — а корпус состоял из статей: сигнал «тему подхватили разработчики» не мог
 * появиться, потому что github до сбора не доживал.
 */
class CorpusBudgetSharingTest {

    private static final int BUDGET = 600;

    @Test
    void каждыйИсточникПолучаетСвоюДолюБюджета() {
        var asked = collectWith(List.of(200, 200, 200, 200, 200, 200));

        // Шесть источников, шестьсот документов: первому достаётся сотня, а не все шестьсот.
        assertThat(asked.keySet()).containsExactlyInAnyOrder("s1", "s2", "s3", "s4", "s5", "s6");
        assertThat(asked.values()).allSatisfy(limit -> assertThat(limit).isLessThanOrEqualTo(100));
    }

    @Test
    void неизрасходованнаяДоляДостаётсяТемКтоВыбралСвою() {
        // Пять источников отдали по одному документу вместо ста, шестой выбрал свою сотню целиком.
        // Доля малых не пропадает: вторым кругом шестого спрашивают на весь недобор — иначе деление
        // поровну обернулось бы недобором корпуса там, где источники просто малы. Источники идут
        // параллельно, поэтому остаток раздаётся не «следующему», а тем, кто упёрся в свою долю.
        var asked = collectWith(List.of(1, 1, 1, 1, 1, 1000));

        assertThat(asked).containsKeys("s1", "s6");
        assertThat(asked.get("s1")).isEqualTo(100);
        assertThat(asked.get("s6")).isEqualTo(BUDGET - 5);
    }

    @Test
    void глубокоеИсследованиеВторогоКругаНеПолучаетАЕгоДоляНеРаздаётся() {
        // Исследование идёт рядом со второй фазой, и она его не ждёт: сколько оно принесёт, к её
        // началу неизвестно. Поэтому его доля считается израсходованной целиком — иначе второй
        // круг раздал бы её другим, и вместе с исследованием корпус вышел бы за бюджет. Второй круг
        // самому исследованию не достаётся никогда: повторный прогон — это ещё шесть или двадцать
        // минут.
        var times = new LinkedHashMap<String, Integer>();
        var asked = collectWith(List.of("s1", "s2", "deepresearch"), List.of(1, 1000, 1000), times);

        assertThat(times).containsEntry("deepresearch", 1).containsEntry("s1", 1);
        // Доля — 200. Второй круг получает s2: недобор s1 (199), но не доля исследования.
        assertThat(asked.get("deepresearch")).isEqualTo(200);
        assertThat(asked.get("s2")).isEqualTo(200 + 199);
    }

    @Test
    void общийБюджетНеПревышается() {
        var asked = collectWith(List.of(200, 200, 200, 200, 200, 200));

        assertThat(asked.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(BUDGET);
    }

    /**
     * Прогоняет сбор по шести источникам, каждый из которых отдаёт столько документов, сколько
     * сказано, и возвращает лимит, с которым спросили каждого.
     */
    private static Map<String, Integer> collectWith(List<Integer> yields) {
        var ids = new ArrayList<String>();
        for (int i = 1; i <= yields.size(); i++) {
            ids.add("s" + i);
        }
        return collectWith(ids, yields, new LinkedHashMap<>());
    }

    /** То же по названным источникам; {@code times} — сколько раз спросили каждый. */
    private static Map<String, Integer> collectWith(
            List<String> ids, List<Integer> yields, Map<String, Integer> times) {
        var asked = new LinkedHashMap<String, Integer>();
        var connectors = new ArrayList<NormalizingSourceConnector>();
        ids.forEach(id -> connectors.add(new FakeConnector(id)));

        var catalog = mock(ConnectorCatalog.class);
        when(catalog.connectorsFor(any())).thenReturn(connectors);

        var collection = mock(ConnectorCollectionService.class);
        when(collection.startRun(any(), any(), any())).thenReturn(null);
        when(collection.execute(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            NormalizingSourceConnector connector = invocation.getArgument(0);
            CollectionRequest request = invocation.getArgument(1);
            String sourceId = connector.descriptor().id();
            synchronized (asked) {
                asked.put(sourceId, request.maxDocuments());
                times.merge(sourceId, 1, Integer::sum);
            }
            int yield = Math.min(yields.get(connectors.indexOf(connector)), request.maxDocuments());
            List<UUID> documents = new ArrayList<>(yield);
            for (int i = 0; i < yield; i++) {
                documents.add(UUID.randomUUID());
            }
            return CollectionOutcome.succeeded(sourceId, UUID.randomUUID(), documents, RunCounters.ZERO);
        });

        var publisher = mock(CorpusResultPublisher.class);
        when(publisher.alreadyPublished(anyString())).thenReturn(false);

        new CollectDomainCorpusUseCase(catalog, collection, publisher, new Uuid7(java.time.Clock.systemUTC()))
                .handle(command());
        return asked;
    }

    private static CollectDomainCorpusCommand command() {
        return new CollectDomainCorpusCommand(
                UUID.randomUUID(),
                1,
                "квантовые вычисления",
                "квантовые вычисления",
                "ru",
                LocalDate.of(2019, 1, 1),
                LocalDate.of(2026, 1, 1),
                Set.of(),
                BUDGET,
                List.of("quantum computing"));
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
        public java.util.stream.Stream<dev.horizon.ingestion.domain.port.RawDocument> fetch(
                CollectionRequest request, Cursor cursor) {
            throw new UnsupportedOperationException("сбор в этом тесте подменён");
        }
    }
}
