package dev.horizon.trends.domain.research;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Режим анализа: сколько времени отчёт вправе занять.
 *
 * <p>{@code fast} укладывает весь анализ в срок ТЗ — двадцать минут. {@code quality} даёт до сорока:
 * сбор читает больше (прежде всего глубокое исследование), и отчёт получается полнее ценой ожидания.
 *
 * <p>Режим входит в {@link AnalysisParameters}, а не приписан к запросу сбоку: он меняет корпус, а с
 * ним и сам ответ. Быстрый и качественный отчёты об одном направлении — два разных ответа, и выдать
 * готовый быстрый тому, кто согласился ждать качественного, значит обмануть его ожидание молча.
 *
 * <p>Имена на проводе — строчные, как в {@code contracts/openapi/horizon-api.yaml} и в
 * {@code contracts/schemas/collect-domain-corpus.command.json}. Хранятся явно, а не выводятся из
 * {@code name().toLowerCase()}: переименование константы не должно молча менять контракт.
 */
public enum AnalysisMode {
    FAST("fast"),
    QUALITY("quality");

    /** Режим, когда никто не попросил другого, — тот, что укладывается в срок ТЗ. */
    public static final AnalysisMode DEFAULT = FAST;

    private final String wireName;

    AnalysisMode(String wireName) {
        this.wireName = wireName;
    }

    /** Имя, которым режим называется в API, в команде сбора и в отчёте. */
    public String wireName() {
        return wireName;
    }

    /**
     * Разбирает режим из запроса аналитика, отказывая на незнакомом.
     *
     * <p>Отказ, а не откат к умолчанию: попросивший {@code quality} и молча получивший быстрый отчёт
     * узнать об этом не может — отчёт выглядит настоящим. Сообщение называет известные имена, чтобы
     * не заставлять угадывать.
     *
     * @param value имя с провода; {@code null} и пустая строка означают «режима не просили»
     * @throws IllegalArgumentException имя задано, но режима с таким именем нет (HTTP 400)
     */
    public static AnalysisMode of(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return find(value)
                .orElseThrow(() -> new IllegalArgumentException("mode: неизвестный режим «%s», допустимы: %s"
                        .formatted(
                                value,
                                Arrays.stream(values())
                                        .map(AnalysisMode::wireName)
                                        .collect(Collectors.joining(", ")))));
    }

    /**
     * Режим из ранее сохранённой строки.
     *
     * <p>Не отказывает, в отличие от {@link #of(String)}: всё записанное до появления режимов поля не
     * несёт, и посчитано оно было в срок быстрого режима. Уронить чтение сохранённого направления или
     * готового отчёта из-за этого нельзя.
     */
    public static AnalysisMode stored(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return find(value).orElse(DEFAULT);
    }

    private static java.util.Optional<AnalysisMode> find(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireName.equals(normalized))
                .findFirst();
    }
}
