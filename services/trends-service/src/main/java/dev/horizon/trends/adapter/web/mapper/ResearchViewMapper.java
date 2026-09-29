package dev.horizon.trends.adapter.web.mapper;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.horizon.trends.adapter.web.dto.AnalysisParametersDto;
import dev.horizon.trends.adapter.web.dto.AnalysisProgressView;
import dev.horizon.trends.adapter.web.dto.FailureInfoView;
import dev.horizon.trends.adapter.web.dto.ResearchRequestView;
import dev.horizon.trends.application.usecase.SubmitResearchRequestUseCase;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ResearchRequest;

/**
 * Maps the {@link ResearchRequest} aggregate onto its published representation.
 *
 * <p>Takes a {@link Clock} because {@code etaSeconds} is derived from "now": the estimate is a
 * property of the moment the response is produced, not of the stored state, and injecting time keeps
 * the mapping deterministic under test.
 */
@Component
public class ResearchViewMapper {

    private final Clock clock;

    public ResearchViewMapper(Clock clock) {
        this.clock = clock;
    }

    /** Reads of an existing request: no submission happened, so there is no outcome to report. */
    public ResearchRequestView toView(ResearchRequest request) {
        return toView(request, (SubmitResearchRequestUseCase.Result.Outcome) null);
    }

    /**
     * The list view, which now spans the organisation and therefore has to say whose row this is
     * (BR-A53).
     *
     * <p>Only "yours or a colleague's", not a name: the name lives in the IAM context, and copying it
     * here would be a second source of truth that goes stale the first time somebody is renamed.
     * What the system can state without asking anybody is exactly this much.
     */
    public ResearchRequestView toView(ResearchRequest request, UUID callerId) {
        var view = toView(request, (SubmitResearchRequestUseCase.Result.Outcome) null);
        return new ResearchRequestView(
                view.id(),
                view.query(),
                view.normalizedQuery(),
                view.parameters(),
                view.status(),
                view.progress(),
                view.reportId(),
                view.partial(),
                view.fromCache(),
                view.outcome(),
                request.requester().userId().equals(callerId),
                request.requester().userId(),
                view.failure(),
                view.submittedAt(),
                view.finishedAt(),
                view.etaSeconds());
    }

    public ResearchRequestView toView(ResearchRequest request, SubmitResearchRequestUseCase.Result.Outcome outcome) {
        var progress = request.progress();
        return new ResearchRequestView(
                request.id().value(),
                request.query().raw(),
                request.query().normalized(),
                AnalysisParametersDto.from(request.parameters()),
                request.status().name(),
                new AnalysisProgressView(
                        progress.stage().name(), progress.percent(), progress.message(), progress.updatedAt()),
                request.reportId().map(TrendReportId::value).orElse(null),
                request.partial(),
                outcome != null && outcome != SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED,
                outcome == null ? null : outcome.wireName(),
                // Авторство здесь не заполняется: у одного запроса вопрос «чей он» не стоит — на эту
                // страницу приходят по ссылке на конкретный расчёт. Его проставляет список.
                null,
                null,
                request.failure()
                        .map(failure -> new FailureInfoView(failure.code(), failure.message(), failure.retryable()))
                        .orElse(null),
                request.submittedAt(),
                request.finishedAt().orElse(null),
                request.etaSeconds(clock.instant()).orElse(null));
    }
}
