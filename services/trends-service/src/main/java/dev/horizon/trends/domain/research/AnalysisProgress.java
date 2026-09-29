package dev.horizon.trends.domain.research;

import java.time.Instant;

import dev.horizon.platform.common.util.Guards;

/**
 * Immutable progress snapshot.
 *
 * <p>Invariant I5 (progress never goes backwards within an attempt) is enforced by
 * {@link #advanceTo}, which keeps the maximum rather than trusting the reported value — out-of-order
 * delivery of progress events is normal with at-least-once messaging and must not make the bar jump
 * backwards in the UI.
 */
public record AnalysisProgress(AnalysisStage stage, int percent, String message, Instant updatedAt) {

    public AnalysisProgress {
        Guards.requireNonNull(stage, "stage");
        Guards.requireRange(percent, "percent", 0, 100);
        Guards.requireNonNull(updatedAt, "updatedAt");
        if (message != null && message.length() > 300) {
            message = message.substring(0, 300);
        }
    }

    public static AnalysisProgress queued(Instant at) {
        return new AnalysisProgress(AnalysisStage.QUEUED, 0, "Запрос принят в обработку", at);
    }

    public AnalysisProgress advanceTo(AnalysisStage nextStage, int nextPercent, String nextMessage, Instant at) {
        int candidate = Math.max(this.percent, nextPercent);
        return new AnalysisProgress(nextStage, candidate, nextMessage, at);
    }

    public AnalysisProgress completed(Instant at) {
        return new AnalysisProgress(AnalysisStage.DONE, 100, "Анализ завершён", at);
    }
}
