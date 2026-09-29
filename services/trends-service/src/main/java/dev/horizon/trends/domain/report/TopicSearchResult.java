package dev.horizon.trends.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Найденные темы, сведённые к ответу (BR-A70, BR-A71, BR-A72).
 *
 * <p>Чистая функция над списком вхождений: база отвечает за отбор и границу доступа, домен — за то,
 * как это читается. Разделено так потому, что порядок здесь — утверждение о смысле, а не деталь
 * плана запроса, и проверять его нужно без базы.
 *
 * @param topics темы, сильнейшая первой
 * @param totalTopics сколько тем нашлось всего — знаменатель для показанных. Без него «Найдено тем:
 *     25» выдавало бы показанное за найденное, а сорок первая тема исчезала бы бесследно
 * @param totalOccurrences сколько вхождений просмотрено. Не размер корпуса: само это число упирается
 *     в предел выборки, поэтому отвечает на вопрос «сколько мы посмотрели», а не «сколько их есть»
 * @param truncated упёрлись ли в предел выборки строк: молча показанная часть корпуса выглядела бы
 *     как весь корпус
 */
public record TopicSearchResult(List<Topic> topics, int totalTopics, int totalOccurrences, boolean truncated) {

    /** Сколько вхождений показывается по одной теме, прежде чем остальные сворачиваются в счётчик. */
    public static final int OCCURRENCES_SHOWN = 5;

    /**
     * @param directions в скольких разных направлениях тема встретилась — главный признак силы
     * @param hiddenOccurrences сколько вхождений не показано; ноль, если показаны все
     */
    public record Topic(
            String trendKey,
            String title,
            int directions,
            int bestRank,
            List<TopicOccurrence> occurrences,
            int hiddenOccurrences) {}

    /**
     * @param occurrences вхождения в произвольном порядке
     * @param limit сколько тем показать
     * @param truncated упёрся ли отбор в предел строк
     */
    public static TopicSearchResult of(List<TopicOccurrence> occurrences, int limit, boolean truncated) {
        var byKey = new LinkedHashMap<String, List<TopicOccurrence>>();
        for (var occurrence : occurrences) {
            byKey.computeIfAbsent(occurrence.trendKey(), key -> new ArrayList<>())
                    .add(occurrence);
        }

        var topics = new ArrayList<Topic>();
        for (var entry : byKey.entrySet()) {
            var sorted = entry.getValue().stream().sorted(byRecency()).toList();
            var directions = sorted.stream()
                    .map(TopicOccurrence::normalizedQuery)
                    .distinct()
                    .count();
            int bestRank = sorted.stream().mapToInt(TopicOccurrence::rank).min().orElseThrow();
            // Название берётся у свежайшего вхождения: тема могла быть переименована между версиями,
            // и показывать старое имя значило бы отвечать про прошлое.
            topics.add(new Topic(
                    entry.getKey(),
                    sorted.get(0).title(),
                    (int) directions,
                    bestRank,
                    sorted.stream().limit(OCCURRENCES_SHOWN).toList(),
                    Math.max(0, sorted.size() - OCCURRENCES_SHOWN)));
        }

        topics.sort(byStrength());
        return new TopicSearchResult(
                List.copyOf(topics.stream().limit(limit).toList()), topics.size(), occurrences.size(), truncated);
    }

    /**
     * Свежее — выше. Тай-брейки по месту и отчёту заданы явно (ADR-0015): без них два вхождения с
     * одинаковой датой менялись бы местами между одинаковыми запросами.
     */
    private static Comparator<TopicOccurrence> byRecency() {
        return Comparator.comparing(TopicOccurrence::generatedAt)
                .reversed()
                .thenComparingInt(TopicOccurrence::rank)
                .thenComparing(occurrence -> occurrence.reportId().toString());
    }

    /**
     * Сначала то, что всплыло независимо в разных направлениях: тема, найденная в одном
     * направлении, — наблюдение, та же тема в двух несмежных — конвергенция, и именно её методология
     * не видит по построению, потому что считает всё внутри корпуса одного запроса.
     */
    private static Comparator<Topic> byStrength() {
        return Comparator.comparingInt(Topic::directions)
                .reversed()
                .thenComparingInt(Topic::bestRank)
                .thenComparing(Topic::trendKey);
    }
}
