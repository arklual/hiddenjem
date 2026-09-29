package dev.horizon.trends.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.TermTraceExplainer;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * "I expected to see this technology — why is it not in my report?" (BR-B4, explainability).
 *
 * <p>Answering this is the difference between a ranking an analyst can defend to an investment
 * committee and one they can only quote. The engine records the fate of a watched term at every
 * stage it could be dropped; this use case makes that reachable for a report the caller may read,
 * against the exact run that produced it.
 *
 * <p>Reuses {@link GetTrendReportUseCase} for access control rather than re-deriving it: an
 * explanation reveals what the corpus contains, so it must be no more visible than the report
 * itself. A second copy of that rule is a second place for it to drift.
 */
@Service
public class ExplainAbsentTermUseCase {

    /** Kept in step with the engine's own bound; each term costs a full replay of the analysis. */
    public static final int MAX_TERMS = 5;

    private final GetTrendReportUseCase reports;
    private final ResearchRequestRepository requests;
    private final MethodologyProfileRepository profiles;
    private final TermTraceExplainer explainer;
    /** Короткая транзакция только на чтение отчёта и запуска — см. {@link #explain}. */
    private final ReadOnly readOnly;

    /** Как выполнить чтение в транзакции; в модульных тестах — просто вызов. */
    @FunctionalInterface
    interface ReadOnly {
        <T> T run(Supplier<T> work);
    }

    @Autowired
    public ExplainAbsentTermUseCase(
            GetTrendReportUseCase reports,
            ResearchRequestRepository requests,
            MethodologyProfileRepository profiles,
            TermTraceExplainer explainer,
            PlatformTransactionManager transactions) {
        this(reports, requests, profiles, explainer, readOnlyIn(transactions));
    }

    ExplainAbsentTermUseCase(
            GetTrendReportUseCase reports,
            ResearchRequestRepository requests,
            MethodologyProfileRepository profiles,
            TermTraceExplainer explainer) {
        this(reports, requests, profiles, explainer, Supplier::get);
    }

    ExplainAbsentTermUseCase(
            GetTrendReportUseCase reports,
            ResearchRequestRepository requests,
            MethodologyProfileRepository profiles,
            TermTraceExplainer explainer,
            ReadOnly readOnly) {
        this.reports = reports;
        this.requests = requests;
        this.profiles = profiles;
        this.explainer = explainer;
        this.readOnly = readOnly;
    }

    private static ReadOnly readOnlyIn(PlatformTransactionManager transactions) {
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return new ReadOnly() {
            @Override
            public <T> T run(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    /**
     * Без транзакции вокруг вызова движка — намеренно.
     *
     * <p>Повтор анализа идёт от полуминуты до нескольких минут, и прежде всё это время держалась
     * открытая транзакция только на чтение. База закрывает простаивающую транзакцию по таймауту
     * роли; движок отвечал, а коммит пустой транзакции падал с «terminating connection due to
     * idle-in-transaction timeout», и аналитик получал ошибку вместо готового ответа (стенд
     * 2026-09-28). Теперь в транзакции только чтение отчёта и запуска, а ожидание движка — вне её.
     */
    public TermTraceExplainer.Explanation explain(TrendReportId reportId, List<String> terms, ReportViewer viewer) {
        var replay = readOnly.run(() -> replayOf(reportId, viewer, cleaned(terms)));
        return engine(() -> explainer.explain(replay));
    }

    /**
     * То же без ожидания: ответ, если движок его уже дал, иначе {@link Optional#empty()} — повтор
     * запущен, клиент спросит снова. Этим пользуется API: минутный HTTP-запрос обрывают прокси.
     */
    public Optional<TermTraceExplainer.Explanation> explainIfReady(
            TrendReportId reportId, List<String> terms, ReportViewer viewer) {
        var replay = readOnly.run(() -> replayOf(reportId, viewer, cleaned(terms)));
        return engine(() -> explainer.explainIfReady(replay));
    }

    private static List<String> cleaned(List<String> terms) {
        var cleaned = terms.stream()
                .map(term -> term == null ? "" : term.strip())
                .filter(term -> !term.isEmpty())
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            throw HorizonException.validation("Укажите хотя бы один термин", List.of());
        }
        if (cleaned.size() > MAX_TERMS) {
            throw HorizonException.validation("Не более " + MAX_TERMS + " терминов за запрос", List.of());
        }
        return cleaned;
    }

    private static <T> T engine(Supplier<T> call) {
        try {
            return call.get();
        } catch (TermTraceExplainer.ExplanationUnavailableException e) {
            // Translated here rather than in the adapter: the adapter knows the call failed, the
            // application layer knows what that means for the caller — a diagnostic they may retry,
            // not a broken report.
            throw new HorizonException(
                    ProblemType.UPSTREAM_UNAVAILABLE, "Движок анализа сейчас не отвечает на запрос трассировки", e);
        }
    }

    private TermTraceExplainer.Request replayOf(TrendReportId reportId, ReportViewer viewer, List<String> cleaned) {
        var report = reports.get(reportId, viewer);
        var request = requests.findById(report.researchRequestId())
                .orElseThrow(() -> HorizonException.notFound("Отчёт", reportId));
        // Without the snapshot there is nothing to replay against. This is reachable only for a
        // report whose request predates corpus collection, which should not exist — but answering
        // against a *different* corpus would be worse than declining, because it would look right.
        var snapshotId = request.corpusSnapshotId()
                .orElseThrow(() ->
                        HorizonException.conflict("Для этого отчёта не сохранён срез корпуса, трассировка невозможна"));

        var profile =
                profiles.findById(request.parameters().methodologyProfileId()).orElseGet(profiles::requireDefault);

        return new TermTraceExplainer.Request(
                request.id().toString(),
                request.attempt(),
                snapshotId,
                request.query().raw(),
                request.query().normalized(),
                request.parameters(),
                profile,
                cleaned);
    }
}
