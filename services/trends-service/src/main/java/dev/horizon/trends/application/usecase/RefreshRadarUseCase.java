package dev.horizon.trends.application.usecase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.saveddomain.SavedDomain;

/**
 * Puts every tracked direction back on the queue in one action (UC-14, BR-A19…BR-A21).
 *
 * <p>A portfolio screen is useful exactly as far as it is fresh, and freshness was being maintained
 * by hand: open a direction, start the analysis, wait up to ninety seconds, come back, open the
 * next. At three directions that is tolerable; at twenty it is work nobody does, and the radar
 * becomes a screen showing last quarter with a confident face. The previous increment created this
 * gap by making a portfolio view at all.
 *
 * <p>Every step goes through {@link SubmitResearchRequestUseCase} rather than round it. A faster
 * path of its own would drift from the single request on quota and on the guard against a second
 * parallel run — the exact rules that have already once disagreed between two copies in this
 * codebase. Freshness is the one thing it deliberately does not share: this button exists to
 * recompute, and answering "already fresh" to all twenty directions is the same as doing nothing.
 */
@Service
public class RefreshRadarUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshRadarUseCase.class);

    private final SavedDomainRepository savedDomains;
    private final SubmitResearchRequestUseCase submit;

    public RefreshRadarUseCase(SavedDomainRepository savedDomains, SubmitResearchRequestUseCase submit) {
        this.savedDomains = savedDomains;
        this.submit = submit;
    }

    /** What happened to one direction. Not an error type: three of the four are normal outcomes. */
    public enum Outcome {
        /** Queued for analysis. */
        ACCEPTED,
        /** An analysis of this direction was already under way; no second one was started. */
        ALREADY_RUNNING,
        /** The hourly budget is spent; this direction was not queued. */
        QUOTA_EXCEEDED,
        /** Refused for a reason of its own — reported per direction rather than failing the batch. */
        FAILED
    }

    public record RefreshResult(UUID savedDomainId, String query, Outcome outcome, UUID requestId, String reason) {}

    /**
     * Queue every saved direction, in the order they were saved.
     *
     * <p>A refusal on one direction does not undo the others: this is N independent requests, not a
     * transaction, and rolling back the ones already accepted would be harm with no purpose. Running
     * out of quota likewise does not stop the loop — the remaining directions will report the same
     * outcome, and seeing which ones did not make it is the point of returning a row per direction.
     */
    public List<RefreshResult> refresh(RequesterRef requester) {
        var results = new ArrayList<RefreshResult>();
        // Oldest first. The repository serves the list newest-first because that is what the screen
        // shows, but when the budget runs out mid-batch the direction left behind should be the one
        // added last, not the one the analyst has been tracking longest.
        var ordered = new ArrayList<>(savedDomains.findByUser(requester.userId()));
        ordered.sort(java.util.Comparator.comparing(SavedDomain::createdAt));
        for (var saved : ordered) {
            results.add(refreshOne(requester, saved.id(), saved.query().raw(), saved.parameters()));
        }
        return List.copyOf(results);
    }

    private RefreshResult refreshOne(
            RequesterRef requester,
            UUID savedDomainId,
            String query,
            dev.horizon.trends.domain.research.AnalysisParameters parameters) {
        try {
            // No idempotency key: the analyst is asking for a recomputation now, and a key derived
            // from the direction would make the second press of the button a silent no-op.
            // Принудительно: до сих пор кнопка отвечала «уже свежее» на все двадцать направлений,
            // то есть та самая кнопка, которая существует ради обновления, не обновляла ничего.
            var result = submit.submit(requester, query, parameters, null, true);
            // Three distinct answers, because they ask three different things of the analyst: wait
            // for a run that just started, wait for one already going, or open the report that
            // already exists.
            // Exhaustive on purpose: with a `default`, the next outcome added would silently be
            // reported as "поставлено на пересчёт" — a queue position that was never taken.
            var outcome =
                    switch (result.outcome()) {
                        case ALREADY_RUNNING -> Outcome.ALREADY_RUNNING;
                            // REUSED_FRESH_RESULT нельзя получить: пересчёт принудительный. Ветка есть,
                            // потому что switch исчерпывающий, а исход остаётся в перечислении исходов
                            // одиночного запроса, где он достижим.
                        case ACCEPTED, IDEMPOTENT_REPLAY, REUSED_FRESH_RESULT -> Outcome.ACCEPTED;
                    };
            return new RefreshResult(
                    savedDomainId, query, outcome, result.request().id().value(), null);
        } catch (HorizonException e) {
            var outcome = e.type() == ProblemType.QUOTA_EXCEEDED ? Outcome.QUOTA_EXCEEDED : Outcome.FAILED;
            log.info("Направление {} не поставлено: {}", query, e.getMessage());
            return new RefreshResult(savedDomainId, query, outcome, null, e.getMessage());
        } catch (RuntimeException e) {
            // Anything the batch did not foresee — a failed write to the outbox, a lost connection.
            // Letting it escape would answer 500 and hide the directions already accepted, turning
            // "one refusal does not undo the others" into "one refusal hides the others".
            log.warn("Направление {} не поставлено из-за непредвиденной ошибки", query, e);
            return new RefreshResult(savedDomainId, query, Outcome.FAILED, null, "непредвиденная ошибка");
        }
    }
}
