package dev.horizon.trends.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.research.AnalysisParameters;

/**
 * Asks the engine what happened to specific terms in an analysis.
 *
 * <p>This answers the question every ranking product is eventually asked and usually cannot answer:
 * <em>"I know this technology exists — why is it not in my report?"</em> The usual response is a
 * shrug or a reworded query until the term appears, and that is precisely when an analyst stops
 * believing the ranking. Being able to name the stage that removed a term, and the number that
 * decided it, turns an opaque verdict into a reviewable one.
 *
 * <p>Unlike the analysis itself, which travels as a command over Kafka because nobody waits for it,
 * an explanation is synchronous: a person is looking at the screen. The port hides that difference
 * so the use case does not encode a transport decision.
 */
public interface TermTraceExplainer {

    /**
     * Replay {@code request}'s analysis with {@code terms} watched.
     *
     * @throws ExplanationUnavailableException if the engine cannot be reached or refuses the request
     */
    Explanation explain(Request request);

    /**
     * То же, но не дожидаясь движка: ответ, если он готов, иначе {@link Optional#empty()} — повтор
     * запущен или уже идёт, спросите ещё раз.
     *
     * <p>Повтор анализа идёт минутами, а HTTP-запрос, который столько молчит, обрывает любой
     * промежуточный прокси: у аналитиков за VPN соединение рвалось на шестнадцатой секунде, и они
     * видели 502 при исправном движке (стенд 2026-09-28). Короткие опросы до готовности проходят
     * через любой посредник. Умолчание честно ждёт — оно для реализаций без фонового прогона.
     *
     * @throws ExplanationUnavailableException если прогон завершился отказом
     */
    default Optional<Explanation> explainIfReady(Request request) {
        return Optional.of(explain(request));
    }

    /**
     * Everything needed to reproduce the exact run being explained.
     *
     * <p>Every field is load-bearing: an explanation computed against a different snapshot, window
     * or profile would be a truthful answer to a question nobody asked, and it would be indeed
     * worse than no answer because it would look right.
     */
    record Request(
            String researchRequestId,
            int attempt,
            UUID snapshotId,
            String query,
            String normalizedQuery,
            AnalysisParameters parameters,
            MethodologyProfile profile,
            List<String> terms) {}

    /**
     * @param stages every pipeline stage in order, as the engine names them — the client renders the
     *     journey from this rather than from a hardcoded copy that would silently fall behind
     * @param traces what happened to each requested term
     */
    record Explanation(List<String> stages, List<TermTrace> traces) {}

    /**
     * @param term the term as the engine keyed it
     * @param canonicalTerm the term it was merged into, when synonym merging moved it
     * @param stage the last stage it reached; the report's own terms end at the final stage
     * @param outcome what happened there
     * @param reason why, in words an analyst can act on
     * @param detail the numbers that decided it — thresholds and measured values
     * @param inReport whether it survived all the way into the report
     */
    record TermTrace(
            String term,
            String canonicalTerm,
            String stage,
            String outcome,
            String reason,
            Map<String, Object> detail,
            boolean inReport) {}

    /** The engine could not answer. Explanations are a diagnostic, so this must never be fatal. */
    class ExplanationUnavailableException extends RuntimeException {
        public ExplanationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
