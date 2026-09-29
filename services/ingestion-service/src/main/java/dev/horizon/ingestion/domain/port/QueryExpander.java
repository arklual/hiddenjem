package dev.horizon.ingestion.domain.port;

import java.util.List;

/**
 * Разложение направления на узкие поисковые запросы — до того, как спрашивать источники.
 *
 * <p>Сбор спрашивает источники словами направления и получает его центр: самое релевантное по
 * «edge computing» — работы про edge computing вообще. Слабый сигнал живёт на краю поля и в первые
 * тысячи ответов по общим словам не попадает: сравнение с размеченным датасетом нашло в собранном
 * корпусе хотя бы слова лишь у сорока технологий из ста (разбор 90). Узкие запросы по подтемам и
 * стыкам направления достают до краёв.
 *
 * <p>Контракт: модель только предлагает, <b>что спросить</b> — находят источники. Отказ означает
 * пустой список, и сбор идёт как раньше, одним запросом направления.
 */
public interface QueryExpander {

    /** Никаких дополнительных запросов: поведение до появления шага. */
    QueryExpander NONE = (query, targets, limit, languages) -> Expansion.EMPTY;

    /**
     * @param limit сколько подтем; каждая может прийти на нескольких языках
     * @param languages на каких языках спрашивать источники; английский есть всегда
     */
    Expansion expand(String query, List<String> subjectTargets, int limit, List<String> languages);

    /** Предложенные запросы и модель, которая их предложила (раскрытие по ТЗ §3.1). */
    record Expansion(List<ExpandedQuery> queries, String model) {

        public static final Expansion EMPTY = new Expansion(List.of(), null);

        public Expansion {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    /**
     * Один запрос, его группа и язык. Группа: {@code core} — подтема направления, {@code emerging}
     * — узкое и свежее, {@code edge} — стык с соседней областью. Язык — {@code en}, {@code ru} или
     * {@code zh}: русская и китайская формулировки той же подтемы достают работы, которых нет в
     * англоязычной выдаче каталога.
     */
    record ExpandedQuery(String query, String group, String language) {

        public ExpandedQuery {
            language = language == null || language.isBlank() ? "en" : language;
        }

        public ExpandedQuery(String query, String group) {
            this(query, group, "en");
        }
    }
}
