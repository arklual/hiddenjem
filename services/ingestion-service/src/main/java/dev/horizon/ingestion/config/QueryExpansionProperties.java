package dev.horizon.ingestion.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Расширение запроса узкими формулировками перед сбором ({@code horizon.query-expansion.*}).
 *
 * @param enabled спрашивать ли модель о подтемах направления
 * @param nlpUrl адрес сервиса моделей; пустой — расширения нет
 * @param maxQueries сколько узких запросов просить у модели
 * @param budgetShare доля общего бюджета документов, отданная узким запросам; остальное — запросу
 *     направления, как было. Доля, а не «всё, что осталось»: иначе широкий запрос съедал бы бюджет
 *     целиком и до краёв поля дело не доходило бы
 * @param reserveDocuments резерв узких запросов в документах, сверх бюджета запроса направления.
 *     Ноль — резерв берётся долей {@code budgetShare} из общего бюджета, как раньше. Абсолютный
 *     резерв нужен для глубины: доля из пяти тысяч, поделённая на двенадцать формулировок и семь
 *     источников, — двадцать четыре документа на пару, и край поля входил в корпус по одному–шести
 *     документам, ниже порога кандидата (разбор 91)
 * @param perQueryDocuments потолок документов на один узкий запрос в одном источнике: узкий запрос
 *     ценен первыми десятками ответов, дальше релевантность падает до шума
 * @param timeout сколько ждать модель
 * @param languages языки узких запросов; английский есть всегда. Русский и китайский нужны, потому
 *     что непопсовое и важное часто публикуется сначала на родном языке авторов и в англоязычной
 *     выдаче каталога не появляется
 * @param sourceLanguages какие источники спрашивать на каком языке, кроме английского (его — все).
 *     Не каждый каталог ищет по-русски и по-китайски: замер 2026-09-19 — arXiv, Semantic Scholar,
 *     Europe PMC и Hacker News отвечают нулём, GitHub — единицами, Crossref — шумом (он режет
 *     запрос на слова и иероглифы). По заголовку и аннотации точно ищет только OpenAlex
 * @param timeBudget сколько времени сбора отдано узким запросам. Источники с жёсткими лимитами
 *     (arXiv — запрос в три секунды, GitHub без токена — десять в минуту) растягивают десятки
 *     узких запросов на минуты, а сага целиком ограничена дедлайном: оставшиеся формулировки
 *     лучше не спросить, чем потерять отчёт
 */
@ConfigurationProperties(prefix = "horizon.query-expansion")
public record QueryExpansionProperties(
        boolean enabled,
        String nlpUrl,
        int maxQueries,
        double budgetShare,
        int reserveDocuments,
        int perQueryDocuments,
        Duration timeout,
        Duration timeBudget,
        List<String> languages,
        Map<String, List<String>> sourceLanguages) {

    public QueryExpansionProperties {
        nlpUrl = nlpUrl == null ? "" : nlpUrl.trim();
        maxQueries = maxQueries <= 0 ? 12 : Math.min(maxQueries, 30);
        budgetShare = budgetShare <= 0 || budgetShare >= 1 ? 0.4 : budgetShare;
        reserveDocuments = Math.max(0, reserveDocuments);
        perQueryDocuments = perQueryDocuments <= 0 ? 40 : perQueryDocuments;
        timeout = timeout == null ? Duration.ofSeconds(90) : timeout;
        timeBudget = timeBudget == null ? Duration.ofMinutes(6) : timeBudget;
        var ordered = new LinkedHashSet<String>();
        ordered.add("en");
        if (languages != null) {
            languages.stream()
                    .filter(Objects::nonNull)
                    .map(language -> language.trim().toLowerCase(Locale.ROOT))
                    .filter(SUPPORTED_LANGUAGES::contains)
                    .forEach(ordered::add);
        }
        languages = List.copyOf(ordered);
        sourceLanguages = sourceLanguages == null || sourceLanguages.isEmpty()
                ? DEFAULT_SOURCE_LANGUAGES
                : Map.copyOf(sourceLanguages);
    }

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "ru", "zh");

    private static final Map<String, List<String>> DEFAULT_SOURCE_LANGUAGES = Map.of(
            "ru", List.of("openalex"),
            "zh", List.of("openalex"));

    /** Спрашивать ли источник на этом языке. Английский — каждый источник. */
    public boolean asks(String sourceId, String language) {
        if (language == null || "en".equals(language)) {
            return true;
        }
        return sourceLanguages.getOrDefault(language, List.of()).contains(sourceId);
    }

    public boolean active() {
        return enabled && !nlpUrl.isEmpty();
    }

    public static QueryExpansionProperties disabled() {
        return new QueryExpansionProperties(false, "", 0, 0, 0, 0, null, null, null, null);
    }
}
