package dev.horizon.ingestion.connector.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Русские и китайские термины ищутся по заголовку и аннотации, а не по полному тексту.
 *
 * <p>Замер 2026-09-19: по «периферийным вычислениям» китайскими терминами полнотекстовый поиск
 * приносил МРТ коленного сустава и осадки на Тибете.
 */
class OpenAlexNonLatinSearchTest {

    @Test
    void китайскиеТерминыИщутсяПоЗаголовкуИАннотации() {
        assertThat(OpenAlexConnector.nonLatinSearch(request("\"联邦学习\" OR \"无线网络\"", "zh", List.of())))
                .isEqualTo("\"联邦学习\" OR \"无线网络\"");
    }

    @Test
    void русскийЗапросАналитикаСАнглийскимиЦелямиИдётОбычнымПоиском() {
        // Запрос направления пишется по-русски, но в источник уходят английские цели словаря.
        assertThat(OpenAlexConnector.nonLatinSearch(
                        request("периферийные вычисления", "ru", List.of("edge computing"))))
                .isNull();
    }

    @Test
    void английскийУзкийЗапросИдётОбычнымПоиском() {
        assertThat(OpenAlexConnector.nonLatinSearch(request("vehicular fog networks", "en", List.of())))
                .isNull();
    }

    @Test
    void запятаяНеЛомаетФильтр() {
        assertThat(OpenAlexConnector.nonLatinSearch(request("\"федеративное обучение, IoT\"", "ru", List.of())))
                .doesNotContain(",");
    }

    private static CollectionRequest request(String query, String language, List<String> targets) {
        return new CollectionRequest(
                UUID.randomUUID(),
                query,
                query,
                language,
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2026, 9, 1),
                Set.of(),
                80,
                targets);
    }
}
