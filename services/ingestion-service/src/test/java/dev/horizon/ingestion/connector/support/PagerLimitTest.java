package dev.horizon.ingestion.connector.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.RawDocument;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Лимит документов соблюдается посреди страницы.
 *
 * <p>Semantic Scholar отдаёт тысячу записей за страницу, и на узкий запрос с порцией в двадцать
 * четыре коннектор приносил 976: страница дочитывалась до конца. Корпус выходил втрое больше
 * бюджета, а анализ усекал его в порядке поступления — выбрасывая как раз собранное последним.
 */
class PagerLimitTest {

    @Test
    void большаяСтраницаНеДочитываетсяСверхЛимита() {
        var connector = new ThousandPerPage();

        long taken = connector.fetch(request(24), Cursor.start()).count();

        assertThat(taken).isEqualTo(24);
        assertThat(connector.pagesFetched).isEqualTo(1);
    }

    @Test
    void лимитКратныйСтраницеНеПросилЛишнейСтраницы() {
        var connector = new ThousandPerPage();

        long taken = connector.fetch(request(1000), Cursor.start()).count();

        assertThat(taken).isEqualTo(1000);
        assertThat(connector.pagesFetched).isEqualTo(1);
    }

    private static CollectionRequest request(int maxDocuments) {
        return new CollectionRequest(
                UUID.randomUUID(),
                "federated backdoor attacks",
                "federated backdoor attacks",
                "en",
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2026, 9, 1),
                Set.of(),
                maxDocuments,
                List.of());
    }

    private record Raw(String externalId) implements RawDocument {

        @Override
        public String sourceId() {
            return "big";
        }

        @Override
        public Provenance provenance() {
            return null;
        }
    }

    private static final class ThousandPerPage extends AbstractSourceConnector<Raw> {

        private int pagesFetched;

        @Override
        protected DocumentNormalizer<Raw> normalizer() {
            return raw -> {
                throw new UnsupportedOperationException("нормализация в этом тесте не нужна");
            };
        }

        @Override
        protected SourcePage<Raw> fetchPage(CollectionRequest request, Cursor cursor) {
            pagesFetched++;
            List<Raw> items = new ArrayList<>(1000);
            for (int i = 0; i < 1000; i++) {
                items.add(new Raw(pagesFetched + "-" + i));
            }
            return new SourcePage<>(items, Cursor.ofValue(String.valueOf(pagesFetched)), false);
        }

        @Override
        public SourceDescriptor descriptor() {
            return new SourceDescriptor(
                    "big", "big", SourceClass.JOURNAL_ARTICLE, Set.of(SourceClass.JOURNAL_ARTICLE), 60, false, true, null, false);
        }

        @Override
        public boolean supports(CollectionRequest request) {
            return true;
        }
    }
}
