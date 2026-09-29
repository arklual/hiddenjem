package dev.horizon.trends.domain.report;

import dev.horizon.platform.common.util.Guards;

/**
 * One period of the publication dynamics (BR-B2).
 *
 * <p>{@code dov}/{@code dod} are the Degree of Visibility / Degree of Diffusion series from
 * Yoon (2012); they are carried for the weak-signal maps in the UI and are nullable because they are
 * undefined for periods with an empty corpus.
 */
public record TimelinePoint(String period, int documentCount, Double dov, Double dod) {

    public TimelinePoint {
        Guards.requireText(period, "timeline.period");
        Guards.requireArgument(documentCount >= 0, "timeline.documentCount must be non-negative");
    }
}
