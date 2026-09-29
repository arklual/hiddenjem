package dev.horizon.trends.application.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.DirectionVisitRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.report.DirectionOverlap;
import dev.horizon.trends.domain.report.DirectionPortrait;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.ReportDelta;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * The state of everything an analyst tracks, on one screen (UC-13).
 *
 * <p>A bank does not research one direction once. It watches a portfolio of them and wants to open a
 * single page in the morning and see where to spend the day. Until now the product answered "which
 * fifteen topics in this direction" and left the analyst to open each saved direction in turn and
 * compare fifteen cards against last quarter's fifteen by eye.
 *
 * <p>Assembled server-side on purpose. A client rendering this from the existing endpoints would
 * fire two requests per tracked direction — fifty saved directions become a hundred round trips and
 * a screen that fills in over seconds. Every value here is already computed by the report and the
 * delta; this only gathers them.
 */
@Service
public class RadarDigestUseCase {

    private static final Logger log = LoggerFactory.getLogger(RadarDigestUseCase.class);

    /** Weak signals surfaced per direction — enough to recognise, not enough to read instead. */
    private static final int HEADLINE_TRENDS = 3;

    private final SavedDomainRepository savedDomains;
    private final DirectionVisitRepository visits;
    private final GetTrendReportUseCase reports;

    public RadarDigestUseCase(
            SavedDomainRepository savedDomains, GetTrendReportUseCase reports, DirectionVisitRepository visits) {
        this.savedDomains = savedDomains;
        this.visits = visits;
        this.reports = reports;
    }

    /**
     * @param query the direction as the analyst wrote it
     * @param portrait the direction's summary, or {@code null} when no analysis has run yet
     * @param entered topics that were not in the previous version of this report
     * @param left topics that fell out of the top
     * @param headline the first few topics, so a direction is recognisable without opening it
     * @param analysedAt when the report behind these numbers was produced
     */
    public record DirectionDigest(
            UUID savedDomainId,
            String query,
            UUID reportId,
            DirectionPortrait portrait,
            int entered,
            int left,
            List<String> headline,
            Instant analysedAt,
            /**
             * Когда этот аналитик в последний раз открывал направление; {@code null} — ни разу.
             *
             * <p>Отдаётся момент, а не флаг «непросмотрено»: сравнение с {@code analysedAt} — одна
             * операция, и клиент сделает её сам, тогда как флаг сервер не смог бы пересчитать без
             * лишнего вопроса о текущем времени.
             */
            Instant seenAt,
            /**
             * Отчёт этого направления прочитать не удалось.
             *
             * <p>Отличается от «ни разу не анализировали», где {@code reportId} пуст: здесь анализ
             * был, идентификатор известен, а доступа к нему у этого аналитика больше нет — например,
             * после ухода из организации, чей коллега его и запускал. Свести оба случая к одному
             * значило бы сказать «здесь ничего не считали», когда считали.
             *
             * <p>Строка при этом остаётся. Исчезнув, она оставила бы аналитика гадать, сохранял ли он
             * это направление вообще, — та же причина, по которой остаётся строка непроанализированного.
             */
            boolean reportUnavailable) {}

    /**
     * Умышленно без {@code @Transactional}.
     *
     * <p>Общая транзакция сделала бы BR-A66 невыполнимым: адаптер отметок участвовал бы в ней же, и
     * при сбое запроса Spring пометил бы внешнюю транзакцию {@code rollbackOnly}, а PostgreSQL — всё
     * соединение как отменённое. Отметки читаются первыми, значит все последующие чтения радара
     * падали бы там же, и {@code catch} ниже ловил бы исключение уже после того, как экран
     * потерян. Каждое обращение здесь открывает свою короткую транзакцию у своего адаптера.
     *
     * <p>Согласованности это не стоит ничего: на READ COMMITTED каждый оператор и так берёт
     * собственный снимок, так что общая транзакция давала лишь переиспользование соединения.
     */
    public List<DirectionDigest> digest(ReportViewer viewer) {
        var result = new ArrayList<DirectionDigest>();
        // Отметки читаются одним запросом на экран: их столько же, сколько направлений, и по одной
        // на строку означало бы пятьдесят обращений ради подсказки.
        var seen = lastSeenOrNothing(viewer.userId());
        for (var saved : savedDomains.findByUser(viewer.userId())) {
            var reportId = saved.lastReport().orElse(null);
            if (reportId == null) {
                // A tracked direction that has never been analysed is part of the answer: it is the
                // row that tells the analyst there is nothing to look at yet, rather than vanishing
                // and leaving them to wonder whether they saved it at all.
                result.add(new DirectionDigest(
                        saved.id(),
                        saved.query().raw(),
                        null,
                        null,
                        0,
                        0,
                        List.of(),
                        null,
                        seen.get(saved.id()),
                        false));
                continue;
            }
            // Одно недоступное направление не отменяет остальных сорока девяти. Соседний метод
            // `overlap` этот случай уже разбирает — здесь его не было, и любой отчёт, ставший
            // нечитаемым (уход из организации, удаление), ронял весь утренний экран целиком.
            // Экран, который не открывается, хуже экрана с одной честной строкой «недоступно».
            TrendReport report;
            ReportDelta delta;
            try {
                report = reports.get(reportId, viewer);
                delta = reports.delta(reportId, viewer);
            } catch (HorizonException e) {
                log.warn(
                        "Отчёт {} отслеживаемого направления {} недоступен этому аналитику: {}",
                        reportId.value(),
                        saved.id(),
                        e.getMessage());
                result.add(new DirectionDigest(
                        saved.id(),
                        saved.query().raw(),
                        reportId.value(),
                        null,
                        0,
                        0,
                        List.of(),
                        null,
                        seen.get(saved.id()),
                        true));
                continue;
            }
            result.add(new DirectionDigest(
                    saved.id(),
                    saved.query().raw(),
                    reportId.value(),
                    DirectionPortrait.of(report),
                    delta.entered().size(),
                    delta.left().size(),
                    headlineOf(report),
                    report.generatedAt(),
                    seen.get(saved.id()),
                    false));
        }
        return List.copyOf(result);
    }

    /**
     * Topics that surfaced in more than one tracked direction (UC-15).
     *
     * <p>A separate call rather than a field of the digest: the radar opens on every visit, and this
     * answers a different question — one the analyst asks occasionally and deliberately. Folding it
     * in would make every morning's first screen pay for it.
     */
    @Transactional(readOnly = true)
    public DirectionOverlap overlap(ReportViewer viewer) {
        var analysed = new ArrayList<DirectionOverlap.DirectionReport>();
        int skipped = 0;
        for (var saved : savedDomains.findByUser(viewer.userId())) {
            var reportId = saved.lastReport().orElse(null);
            if (reportId == null) {
                // Counted, not included (P6). Dropped in silence, these would turn "no overlap" into
                // a claim about the field, when it is a claim about what has been run.
                skipped++;
                continue;
            }
            try {
                analysed.add(new DirectionOverlap.DirectionReport(
                        saved.id(), saved.query().raw(), reportId.value(), reports.get(reportId, viewer)));
            } catch (HorizonException e) {
                // A direction whose report cannot be read is exactly the case P6 describes — nothing
                // to compare — and it is counted as one. Letting it out would let a single stale
                // pointer take the whole screen down for every other direction.
                log.warn("Направление {} пропущено: отчёт {} недоступен", saved.id(), reportId, e);
                skipped++;
            }
        }
        return DirectionOverlap.of(analysed, skipped);
    }

    /**
     * Отметки визитов или пустота.
     *
     * <p>Пометка — подсказка, а не содержание экрана (P6): радар без неё остаётся радаром, а радар,
     * упавший из-за неё, перестаёт быть чем бы то ни было.
     *
     * <p>Ловится {@link DataAccessException}, а не {@code RuntimeException}: сюда попадает всё, ради
     * чего написан этот метод — недоступная база, таймаут запроса, отсутствующая таблица, — потому
     * что {@code @Repository} на адаптере включает трансляцию исключений персистентности. Широкий
     * перехват выдавал бы ошибку в самой сборке карты за «отметки недоступны» и прятал бы её.
     */
    private Map<UUID, Instant> lastSeenOrNothing(UUID userId) {
        try {
            return visits.lastSeenBy(userId);
        } catch (DataAccessException e) {
            log.warn("Отметки визитов недоступны, радар показан без пометок", e);
            return Map.of();
        }
    }

    /**
     * Аналитик открыл направление — значит увидел его (BR-A64).
     *
     * <p>Отметка ставится этим действием, а не загрузкой радара: радар открывают, чтобы решить, куда
     * смотреть, и пометить в этот момент всё просмотренным значило бы стереть ответ на вопрос, ради
     * которого пришли.
     */
    @Transactional
    public void markSeen(ReportViewer viewer, UUID savedDomainId, Instant now) {
        // Только своё направление: отметка про человека и его портфель, и ставить её на чужую строку
        // не за что. «Нет такого» и «есть, но чужое» отвечают одинаково — иначе ответ подтверждал бы
        // существование чужих направлений.
        if (!savedDomains.existsForUser(viewer.userId(), savedDomainId)) {
            throw HorizonException.notFound("Сохранённое направление", savedDomainId);
        }
        visits.markSeen(viewer.userId(), savedDomainId, now);
    }

    private static List<String> headlineOf(TrendReport report) {
        return report.trends().stream()
                .limit(HEADLINE_TRENDS)
                .map(RankedTrend::title)
                .toList();
    }
}
