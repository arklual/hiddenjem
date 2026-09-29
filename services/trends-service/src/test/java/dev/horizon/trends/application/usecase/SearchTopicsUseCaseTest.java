package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.TopicSearchRepository;
import dev.horizon.trends.domain.report.TopicOccurrence;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * The rules around the search itself (P2, P7).
 *
 * <p>Здесь не проверяется ни отбор, ни порядок: первое исполняется на слое персистентности, второе —
 * в домене. Проверяется то, что живёт только тут: отказ до похода в базу и честность отметки об
 * усечении. Оба числа — минимальная длина и предел строк — иначе не закреплены ничем, и подмена
 * пятисот на пять не уронила бы ни одного теста.
 */
class SearchTopicsUseCaseTest {

    private static final ReportViewer VIEWER = new ReportViewer(UUID.randomUUID(), UUID.randomUUID(), false);

    /** Отдаёт столько вхождений, сколько попросили создать, и запоминает, спрашивали ли вообще. */
    private static final class Corpus implements TopicSearchRepository {
        private final int rows;
        private int calls;

        private Corpus(int rows) {
            this.rows = rows;
        }

        @Override
        public List<TopicOccurrence> findOccurrences(String fragment, ReportViewer viewer, int rowLimit) {
            calls++;
            var found = new ArrayList<TopicOccurrence>();
            for (int i = 0; i < Math.min(rows, rowLimit); i++) {
                found.add(new TopicOccurrence(
                        "тема-" + i,
                        "Тема " + i,
                        1,
                        UUID.randomUUID(),
                        1,
                        "направление",
                        "направление",
                        Instant.parse("2026-03-01T10:00:00Z")));
            }
            return found;
        }
    }

    @Test
    void aSingleCharacterIsRefusedBeforeTheCorpusIsTouched() {
        // P2. Отказ обязан случиться до запроса: одна буква совпадает почти со всем, и «сначала
        // выгрузим, потом откажем» — это и есть та выгрузка, от которой правило заведено.
        var corpus = new Corpus(10);

        assertThatThrownBy(() -> new SearchTopicsUseCase(corpus).search("ф", VIEWER))
                .isInstanceOf(HorizonException.class)
                .hasMessageContaining("не короче");

        assertThat(corpus.calls).isZero();
    }

    @Test
    void spacesAroundTheFragmentDoNotCountAsCharacters() {
        var corpus = new Corpus(1);

        assertThatThrownBy(() -> new SearchTopicsUseCase(corpus).search("  ф  ", VIEWER))
                .isInstanceOf(HorizonException.class);

        assertThat(corpus.calls).isZero();
    }

    @Test
    void aFragmentLongerThanAnyTitleIsRefused() {
        // Название темы — varchar(200), значит запрос длиннее не совпадёт ни с чем гарантированно.
        // Предел объявлен в контракте; объявленное и не проверенное — то же, что не объявленное.
        var corpus = new Corpus(1);

        assertThatThrownBy(() -> new SearchTopicsUseCase(corpus)
                        .search("я".repeat(SearchTopicsUseCase.MAX_FRAGMENT + 1), VIEWER))
                .isInstanceOf(HorizonException.class)
                .hasMessageContaining("не длиннее");

        assertThat(corpus.calls).isZero();
    }

    @Test
    void aFragmentOfExactlyTheMinimumLengthIsAccepted() {
        // Граница включительно: иначе правило «не короче двух» на деле означало бы «не короче трёх».
        var corpus = new Corpus(1);

        assertThat(new SearchTopicsUseCase(corpus).search("ии", VIEWER).topics())
                .hasSize(1);
        assertThat(corpus.calls).isOne();
    }

    @Test
    void aResultThatFitsIsNotCalledTruncated() {
        var result = new SearchTopicsUseCase(new Corpus(SearchTopicsUseCase.ROW_LIMIT - 1)).search("ии", VIEWER);

        assertThat(result.truncated()).isFalse();
    }

    @Test
    void hittingTheRowLimitIsReportedRatherThanHidden() {
        // P7. Ровно на пределе отличить «столько и есть» от «было больше» нельзя, и утверждать
        // первое, имея основание лишь для второго, — то же самое, что промолчать.
        var result = new SearchTopicsUseCase(new Corpus(SearchTopicsUseCase.ROW_LIMIT + 100)).search("ии", VIEWER);

        assertThat(result.truncated()).isTrue();
        assertThat(result.totalOccurrences()).isEqualTo(SearchTopicsUseCase.ROW_LIMIT);
    }

    @Test
    void topicsBeyondTheShownLimitAreCountedRatherThanDropped() {
        // Ревью нашло, что тема сверх лимита исчезала бесследно, и экран выдавал показанное за
        // найденное. Знаменатель теперь есть, и он больше показанного.
        var result = new SearchTopicsUseCase(new Corpus(SearchTopicsUseCase.TOPIC_LIMIT + 5)).search("ии", VIEWER);

        assertThat(result.topics()).hasSize(SearchTopicsUseCase.TOPIC_LIMIT);
        assertThat(result.totalTopics()).isEqualTo(SearchTopicsUseCase.TOPIC_LIMIT + 5);
    }
}
