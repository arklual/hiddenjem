package dev.horizon.trends.adapter.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.platform.spring.caller.Caller;
import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.trends.adapter.web.dto.ReportDeltaView;
import dev.horizon.trends.adapter.web.dto.TermExplanationPendingView;
import dev.horizon.trends.adapter.web.dto.TermExplanationView;
import dev.horizon.trends.adapter.web.dto.TrendReportView;
import dev.horizon.trends.adapter.web.mapper.ReportViewMapper;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.application.usecase.ExplainAbsentTermUseCase;
import dev.horizon.trends.application.usecase.ExportReportUseCase;
import dev.horizon.trends.application.usecase.GetTrendReportUseCase;
import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;

/** Trend reports: read, drill into one trend, export (OpenAPI tag {@code Reports}). */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private static final MediaType TEXT_MARKDOWN_UTF8 = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final GetTrendReportUseCase reports;
    private final FeatureGate features;
    private final ExplainAbsentTermUseCase explanations;
    private final ExportReportUseCase export;
    private final TrendFeedbackRepository feedback;
    private final ReportViewMapper mapper;
    private final CurrentCaller currentUser;

    public ReportController(
            GetTrendReportUseCase reports,
            FeatureGate features,
            ExplainAbsentTermUseCase explanations,
            ExportReportUseCase export,
            TrendFeedbackRepository feedback,
            ReportViewMapper mapper,
            CurrentCaller currentUser) {
        this.reports = reports;
        this.features = features;
        this.explanations = explanations;
        this.export = export;
        this.feedback = feedback;
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    @GetMapping("/{reportId}")
    public TrendReportView get(@PathVariable UUID reportId) {
        Caller caller = currentUser.require();
        var report = reports.get(new TrendReportId(reportId), ReportViewers.of(caller));
        return mapper.toView(report, feedbackOf(caller, report), hiddenFor(caller, report));
    }

    /**
     * What changed since the previous version of this report (UC-12).
     *
     * <p>A separate resource rather than a field of the report: every read would otherwise pay to
     * load a second version for data most requests never look at, and a client can render the report
     * while the delta is still loading.
     */
    @GetMapping("/{reportId}/delta")
    public ReportDeltaView delta(@PathVariable UUID reportId) {
        features.require(FeatureFlag.REPORT_DELTA);
        Caller caller = currentUser.require();
        return ReportDeltaView.from(reports.delta(new TrendReportId(reportId), ReportViewers.of(caller)));
    }

    /**
     * "Why is this technology not in my report?" (BR-B4).
     *
     * <p>A {@code GET} because it reads: nothing is created, and an analyst may bookmark or share
     * the question. The engine answers by replaying the analysis with the terms watched, so the
     * call is slower than the rest of the API by design — the alternative would be storing the fate
     * of every candidate of every report to answer a question asked about two or three of them.
     *
     * <p>Не ждёт движка: повтор идёт минутами, а соединение, молчащее дольше нескольких секунд,
     * обрывают прокси между аналитиком и стендом — он видел 502 при исправном движке. Первый запрос
     * запускает повтор и получает {@code 202} с {@code Retry-After}; тот же запрос позже — {@code 200}
     * с ответом. Одинаковые вопросы сводятся к одному прогону, так что опрос его не множит.
     */
    @GetMapping("/{reportId}/explain")
    public ResponseEntity<Object> explain(@PathVariable UUID reportId, @RequestParam("term") List<String> terms) {
        features.require(FeatureFlag.TERM_TRACE);
        Caller caller = currentUser.require();
        return explanations
                .explainIfReady(new TrendReportId(reportId), terms, ReportViewers.of(caller))
                .<ResponseEntity<Object>>map(explanation -> ResponseEntity.ok(TermExplanationView.from(explanation)))
                .orElseGet(() -> ResponseEntity.accepted()
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(EXPLAIN_POLL_SECONDS))
                        .body(new TermExplanationPendingView("RUNNING", EXPLAIN_POLL_SECONDS)));
    }

    /** Через сколько секунд спросить трассировку снова: прогон — минуты, опрос дешёвый. */
    static final int EXPLAIN_POLL_SECONDS = 3;

    @GetMapping("/{reportId}/trends/{trendKey}")
    public TrendReportView.RankedTrendView getTrend(@PathVariable UUID reportId, @PathVariable String trendKey) {
        Caller caller = currentUser.require();
        var id = new TrendReportId(reportId);
        var report = reports.get(id, ReportViewers.of(caller));
        var trend = report.findByKey(trendKey).orElseThrow(() -> HorizonException.notFound("Тренд", trendKey));
        // Through the same merge as the list: a topic marked in the list and unmarked in its own
        // detail view would be the product contradicting itself on the same screen-flow.
        return mapper.toView(trend, feedbackOf(caller, report).get(trendKey));
    }

    /**
     * Export (FR-03.8).
     *
     * <p>JSON reuses the API representation verbatim rather than defining an export-specific shape:
     * an analyst who scripts against the export and against the API must not have to write two
     * parsers. CSV gets a {@code Content-Disposition} so browsers save it instead of rendering it,
     * and an explicit UTF-8 charset so the BOM written by the use case is interpreted correctly.
     */
    @GetMapping("/{reportId}/export")
    public ResponseEntity<Object> export(@PathVariable UUID reportId, @RequestParam String format) {
        features.require(FeatureFlag.REPORT_EXPORT);
        Caller caller = currentUser.require();
        String requested = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!"json".equals(requested) && !"csv".equals(requested) && !"markdown".equals(requested)) {
            throw new HorizonException(
                    ProblemType.VALIDATION_ERROR, "Поддерживаемые форматы экспорта: json, csv, markdown");
        }

        var report = reports.get(new TrendReportId(reportId), ReportViewers.of(caller));
        if ("markdown".equals(requested)) {
            // Тот же эндпоинт и тот же флаг, что у остальных форматов: отдельный путь означал бы
            // отдельную проверку доступа, а проверок доступа к отчёту должно быть ровно столько,
            // сколько способов его получить, — то есть одна.
            return ResponseEntity.ok()
                    .contentType(TEXT_MARKDOWN_UTF8)
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(report, "md"))
                    .body(export.toMarkdown(report));
        }
        if ("csv".equals(requested)) {
            return ResponseEntity.ok()
                    .contentType(TEXT_CSV_UTF8)
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(report, "csv"))
                    .body(export.toCsv(report));
        }
        // Без разметки, в отличие от чтения. Экспорт — файл, который передают дальше, и вердикты с
        // дословными комментариями аналитика туда попадать не должны; вдобавок отчёт неизменяем, а
        // с разметкой он выгружался бы разными байтами у разных людей и в разное время. CSV её и не
        // содержал — теперь форматы говорят об отчёте одно и то же.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(report, "json"))
                .body(mapper.toView(report, Map.of(), List.of()));
    }

    /**
     * Темы, скрытые из этого отчёта по пометке самого аналитика.
     *
     * <p>Движок убирает их до отбора в ТОП-N, поэтому в отчёте их нет вовсе, а число скрытого он
     * возвращает. Числа недостаточно: «скрыто тем: 3» без имён — оговорка, которую нельзя проверить,
     * и отменить решение по ней тоже нельзя. Фильтр, который нельзя осмотреть, честен наполовину.
     *
     * <p>Считается на стороне представления, а не хранится в отчёте: список зависит от смотрящего,
     * а отчёт неизменяем и общий. У коллеги с другими пометками тот же отчёт покажет другое скрытое
     * — и это верно, потому что скрывал не он. По той же причине здесь нет разметки при выгрузке:
     * файл передают дальше, и мнение одного аналитика не должно уезжать с ним.
     *
     * <p>Из вердиктов берётся только {@code NOISE}: {@code ALREADY_KNOWN} ничего не скрывает.
     * Отсеиваются темы, присутствующие в отчёте, — их пометка видна на самой карточке.
     */
    private List<TrendReportView.HiddenTopicView> hiddenFor(Caller caller, TrendReport report) {
        var present = report.trends().stream().map(RankedTrend::trendKey).collect(Collectors.toSet());
        var hiddenKeys = HiddenTopics.keysToHide(
                feedback.findByDirection(caller.userId(), report.query().normalized()), present);
        if (hiddenKeys.isEmpty()) {
            return List.of();
        }
        return HiddenTopics.describe(
                hiddenKeys, reports.titlesFor(report.query().normalized(), hiddenKeys));
    }

    /**
     * The analyst's marks on this report, plus the ones they made on earlier versions of the same
     * direction (BR-A32).
     *
     * <p>Feedback is stored per report and a recomputation makes a new one, so without carrying, the
     * twenty minutes of triage an analyst spends on a quarterly direction is thrown away every
     * quarter — and after the third they stop doing it.
     */
    private Map<String, ReportViewMapper.Mark> feedbackOf(Caller caller, TrendReport report) {
        return ReportViewMapper.merge(
                feedback.findByUserAndReport(caller.userId(), report.id()),
                feedback.findCarried(caller.userId(), report.query().normalized(), report.id()));
    }

    private static String attachment(TrendReport report, String extension) {
        return "attachment; filename=\"horizon-report-%s.%s\""
                .formatted(report.id().value(), extension);
    }
}
