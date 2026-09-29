package dev.horizon.trends.adapter.web.dto;

import java.util.List;
import java.util.UUID;

import dev.horizon.trends.domain.report.DirectionOverlap;

/**
 * OpenAPI {@code DirectionOverlap} — topics found in more than one tracked direction.
 *
 * <p>Carries no scores on purpose: a score is normalised within its own corpus (§12), so two
 * directions' scores share no scale, and a number placed next to another will be compared by the
 * reader whatever the caption says. Ranks are shown instead — they are statements about position
 * inside one report, which is exactly what they claim to be.
 */
public record DirectionOverlapView(List<SharedTopicView> topics, int directionsCompared, int directionsSkipped) {

    public record SharedTopicView(String trendKey, String title, List<AppearanceView> appearances) {}

    public record AppearanceView(UUID savedDomainId, String query, UUID reportId, int rank) {}

    public static DirectionOverlapView from(DirectionOverlap overlap) {
        return new DirectionOverlapView(
                overlap.topics().stream()
                        .map(topic -> new SharedTopicView(
                                topic.trendKey(),
                                topic.title(),
                                topic.appearances().stream()
                                        .map(appearance -> new AppearanceView(
                                                appearance.savedDomainId(),
                                                appearance.query(),
                                                appearance.reportId(),
                                                appearance.rank()))
                                        .toList()))
                        .toList(),
                overlap.directionsCompared(),
                overlap.directionsSkipped());
    }
}
