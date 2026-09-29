package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.horizon.trends.domain.report.TopicSearchResult;

/**
 * OpenAPI {@code TopicSearchResult} — что мы уже находили по этому фрагменту.
 *
 * <p>Баллов здесь нет ни одного, как и в пересечении направлений: балл нормирован внутри своего
 * корпуса, и число рядом с темами из разных отчётов выглядело бы сравнением, которым не является.
 * Место в рейтинге сравнимо — оно и показано.
 */
public record TopicSearchView(List<TopicView> topics, int totalTopics, int totalOccurrences, boolean truncated) {

    public record TopicView(
            String trendKey,
            String title,
            int directions,
            int bestRank,
            List<OccurrenceView> occurrences,
            int hiddenOccurrences) {}

    public record OccurrenceView(UUID reportId, int version, String query, int rank, Instant generatedAt) {}

    public static TopicSearchView from(TopicSearchResult result) {
        return new TopicSearchView(
                result.topics().stream()
                        .map(topic -> new TopicView(
                                topic.trendKey(),
                                topic.title(),
                                topic.directions(),
                                topic.bestRank(),
                                topic.occurrences().stream()
                                        .map(occurrence -> new OccurrenceView(
                                                occurrence.reportId(),
                                                occurrence.version(),
                                                occurrence.query(),
                                                occurrence.rank(),
                                                occurrence.generatedAt()))
                                        .toList(),
                                topic.hiddenOccurrences()))
                        .toList(),
                result.totalTopics(),
                result.totalOccurrences(),
                result.truncated());
    }
}
