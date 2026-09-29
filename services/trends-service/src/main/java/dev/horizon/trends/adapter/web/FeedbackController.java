package dev.horizon.trends.adapter.web;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.trends.adapter.web.dto.TrendFeedbackRequestBody;
import dev.horizon.trends.adapter.web.dto.TrendFeedbackView;
import dev.horizon.trends.application.usecase.RecordTrendFeedbackUseCase;
import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.TrendReportId;

/**
 * Analyst verdicts on trends (OpenAPI tag {@code Feedback}).
 *
 * <p>{@code PUT}, not {@code POST}: a verdict is a property of the (analyst, trend) pair with at most
 * one value, so re-rating replaces rather than appends. That makes retries harmless and gives the
 * endpoint the idempotency the contract's 200 response implies.
 */
@RestController
@RequestMapping("/api/v1/reports/{reportId}/trends/{trendKey}/feedback")
public class FeedbackController {

    private final RecordTrendFeedbackUseCase useCase;
    private final FeatureGate features;
    private final CurrentCaller currentUser;

    public FeedbackController(RecordTrendFeedbackUseCase useCase, FeatureGate features, CurrentCaller currentUser) {
        this.useCase = useCase;
        this.features = features;
        this.currentUser = currentUser;
    }

    @PutMapping
    public TrendFeedbackView submit(
            @PathVariable UUID reportId,
            @PathVariable String trendKey,
            @Valid @RequestBody TrendFeedbackRequestBody body) {

        features.require(FeatureFlag.TREND_FEEDBACK);
        var caller = currentUser.require();
        var recorded = useCase.record(
                ReportViewers.of(caller), new TrendReportId(reportId), trendKey, body.verdict(), body.comment());
        // Never carried: this is the verdict the analyst has just given, on this report.
        return TrendFeedbackView.from(recorded, false);
    }

    /**
     * Снять свою пометку с темы.
     *
     * <p>Нужно потому, что пометка «не технология» стала действием с последствиями: тема исчезает из
     * ТОП-N следующего прогона. Действие без отмены — западня, и особенно та, которую видно только
     * после того, как в неё попал.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID reportId, @PathVariable String trendKey) {
        features.require(FeatureFlag.TREND_FEEDBACK);
        useCase.withdraw(ReportViewers.of(currentUser.require()), new TrendReportId(reportId), trendKey);
    }
}
