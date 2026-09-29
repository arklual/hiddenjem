package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.DirectionVisitRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.report.ReportDelta;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.saveddomain.SavedDomain;
import dev.horizon.trends.support.Fixtures;

/**
 * Одно недоступное направление не отменяет остальных.
 *
 * <p>Радар — первый экран рабочего утра: аналитик открывает его, чтобы понять, куда потратить день.
 * Отчёт отслеживаемого направления может стать нечитаемым — например, его запускал коллега, а
 * аналитик вышел из организации. `digest` звал `reports.get` без обработки, и одно такое направление
 * роняло весь экран: пятьдесят строк не показывались из-за одной.
 *
 * <p>Соседний метод `overlap` этот случай уже разбирал и считал такие направления пропущенными.
 * Несоответствие внутри одного класса, и хрупким был тот метод, который открывают чаще.
 *
 * <p>Строка при этом остаётся и называет причину. Исчезнув, она оставила бы аналитика гадать,
 * сохранял ли он направление вообще; сведённая к «ни разу не анализировали» — сказала бы, что здесь
 * ничего не считали, когда считали.
 */
class RadarSurvivesAnUnreadableReportTest {

    private static final UUID UNREADABLE = UUID.fromString("dddddddd-0000-4000-8000-000000000001");
    private static final UUID READABLE = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");

    @Test
    void theOtherDirectionsAreStillShown() {
        var useCase = radarOver(List.of(tracked("недоступное", UNREADABLE), tracked("обычное", READABLE)));

        var rows = useCase.digest(ReportViewer.of(Fixtures.USER_ID));

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> !row.reportUnavailable()).count())
                .isEqualTo(1);
    }

    @Test
    void theUnreadableRowSaysSoInsteadOfVanishing() {
        var rows = radarOver(List.of(tracked("недоступное", UNREADABLE))).digest(ReportViewer.of(Fixtures.USER_ID));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reportUnavailable()).isTrue();
            // Идентификатор остаётся: анализ был, и это отличает строку от непроанализированной.
            assertThat(row.reportId()).isEqualTo(UNREADABLE);
            assertThat(row.portrait()).isNull();
        });
    }

    @Test
    void aDirectionNeverAnalysedIsNotCalledUnavailable() {
        // Два разных факта: «не считали» и «считали, но вам нельзя». Свести их к одному — соврать.
        var rows = radarOver(List.of(tracked("новое", null))).digest(ReportViewer.of(Fixtures.USER_ID));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reportUnavailable()).isFalse();
            assertThat(row.reportId()).isNull();
        });
    }

    private static RadarDigestUseCase radarOver(List<SavedDomain> domains) {
        return new RadarDigestUseCase(new Portfolio(domains), new Reports(), new Visits());
    }

    private static SavedDomain tracked(String query, UUID reportId) {
        var domain = SavedDomain.create(
                Fixtures.USER_ID, TechnologyDomainQuery.of(query), Fixtures.parameters(), Fixtures.NOW);
        return reportId == null ? domain : domain.withLastReport(new TrendReportId(reportId));
    }

    /** Отказывает ровно на одном отчёте — как отказал бы настоящий при отозванном доступе. */
    private static final class Reports extends GetTrendReportUseCase {
        private Reports() {
            super(null, null, null);
        }

        @Override
        public TrendReport get(TrendReportId id, ReportViewer viewer) {
            if (UNREADABLE.equals(id.value())) {
                throw HorizonException.notFound("Отчёт", id);
            }
            return Fixtures.reportWithTrends(Fixtures.pendingRequest(), "em-1.0.0", List.of("тема"), 90.0);
        }

        @Override
        public ReportDelta delta(TrendReportId id, ReportViewer viewer) {
            return ReportDelta.between(get(id, viewer), null);
        }
    }

    private record Portfolio(List<SavedDomain> domains) implements SavedDomainRepository {
        @Override
        public List<SavedDomain> findByUser(UUID userId) {
            return domains;
        }

        @Override
        public Optional<SavedDomain> findByUserAndQuery(UUID userId, String normalizedQuery) {
            return Optional.empty();
        }

        @Override
        public long countByUser(UUID userId) {
            return domains.size();
        }

        @Override
        public SavedDomain save(SavedDomain domain) {
            return domain;
        }

        @Override
        public boolean delete(UUID userId, UUID id) {
            return false;
        }

        @Override
        public boolean existsForUser(UUID userId, UUID id) {
            return true;
        }
    }

    private static final class Visits implements DirectionVisitRepository {
        private final Map<UUID, Instant> marks = new HashMap<>();

        @Override
        public Map<UUID, Instant> lastSeenBy(UUID userId) {
            return Map.copyOf(marks);
        }

        @Override
        public void markSeen(UUID userId, UUID savedDomainId, Instant seenAt) {
            marks.put(savedDomainId, seenAt);
        }
    }
}
