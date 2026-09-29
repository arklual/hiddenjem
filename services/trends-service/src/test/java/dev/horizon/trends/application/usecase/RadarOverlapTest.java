package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.saveddomain.SavedDomain;
import dev.horizon.trends.support.Fixtures;

/**
 * Assembling the overlap from the portfolio (UC-15, P6).
 *
 * <p>The calculation itself is covered in the domain, where the directions arrive as arguments. This
 * covers the step before it — turning "what the analyst tracks" into "what can be compared" — which
 * is where the two counters of BR-A31 are produced and where a direction can quietly disappear.
 */
class RadarOverlapTest {

    private static final UUID OTHER_REPORT = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");

    /** Serves the directions it was given; report lookup is delegated to the stub below. */
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
            return domains.stream().anyMatch(saved -> saved.id().equals(id));
        }
    }

    /** Answers with the given report, or refuses when asked for {@link #OTHER_REPORT}. */
    private static final class Reports extends GetTrendReportUseCase {
        private final TrendReport report;

        private Reports(TrendReport report) {
            super(null, null, null);
            this.report = report;
        }

        @Override
        public TrendReport get(TrendReportId id, ReportViewer viewer) {
            if (OTHER_REPORT.equals(id.value())) {
                throw HorizonException.notFound("Отчёт", id);
            }
            return report;
        }
    }

    /** Записывает отметки в память: этому файлу важно, поставлена ли отметка, а не как она хранится. */
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

    private static SavedDomain tracked(String query, UUID reportId) {
        var domain = SavedDomain.create(
                Fixtures.USER_ID, TechnologyDomainQuery.of(query), Fixtures.parameters(), Fixtures.NOW);
        return reportId == null ? domain : domain.withLastReport(new TrendReportId(reportId));
    }

    private static TrendReport sharedReport() {
        return Fixtures.reportWithTrends(Fixtures.pendingRequest(), "em-1.0.0", List.of("общая тема"), 90.0);
    }

    private static RadarDigestUseCase useCase(List<SavedDomain> domains, TrendReport report) {
        // Отметки визитов к пересечению направлений отношения не имеют: пустая карта, а не мок,
        // потому что подставлять сюда поведение значило бы проверять его здесь.
        return new RadarDigestUseCase(new Portfolio(domains), new Reports(report), new Visits());
    }

    @Test
    void directionsWithAReportAreCompared() {
        // The regression this file exists for: nothing wrote `lastReportId`, so this list was always
        // empty and the answer was always "nothing to compare" — no matter how many analyses had run.
        var overlap = useCase(
                        List.of(
                                tracked("квантовые вычисления", UUID.randomUUID()),
                                tracked("искусственный интеллект", UUID.randomUUID())),
                        sharedReport())
                .overlap(ReportViewer.of(Fixtures.USER_ID));

        assertThat(overlap.directionsCompared()).isEqualTo(2);
        assertThat(overlap.directionsSkipped()).isZero();
        assertThat(overlap.topics()).hasSize(1);
    }

    @Test
    void aDirectionThatWasNeverAnalysedIsCountedRatherThanDropped() {
        // P6. Dropped in silence it would turn "no overlap" into a claim about the field, when it is
        // a claim about what has been run.
        var overlap = useCase(
                        List.of(
                                tracked("квантовые вычисления", UUID.randomUUID()),
                                tracked("искусственный интеллект", null),
                                tracked("биотехнологии", null)),
                        sharedReport())
                .overlap(ReportViewer.of(Fixtures.USER_ID));

        assertThat(overlap.directionsCompared()).isOne();
        assertThat(overlap.directionsSkipped()).isEqualTo(2);
    }

    @Test
    void oneUnreadableReportDoesNotTakeDownTheRest() {
        // A pointer can outlive the report it names. That direction has nothing to compare, which is
        // the same situation P6 describes — and the other directions still have an answer.
        var overlap = useCase(
                        List.of(
                                tracked("квантовые вычисления", UUID.randomUUID()),
                                tracked("искусственный интеллект", OTHER_REPORT)),
                        sharedReport())
                .overlap(ReportViewer.of(Fixtures.USER_ID));

        assertThat(overlap.directionsCompared()).isOne();
        assertThat(overlap.directionsSkipped()).isOne();
    }

    @Test
    void anEmptyPortfolioIsAnAnswerRatherThanAnError() {
        var overlap = useCase(List.of(), sharedReport()).overlap(ReportViewer.of(Fixtures.USER_ID));

        assertThat(overlap.topics()).isEmpty();
        assertThat(overlap.directionsCompared()).isZero();
        assertThat(overlap.directionsSkipped()).isZero();
    }

    @Test
    void aDirectionThatIsNotMineCannotBeMarkedSeen() {
        // Граница доступа, а не удобство: отметка про человека и его портфель. И «нет такого», и
        // «есть, но чужое» отвечают одинаково — иначе ответ подтверждал бы существование чужих
        // направлений тому, кто их перебирает.
        var visits = new Visits();
        var mine = tracked("моё", UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        var useCase = new RadarDigestUseCase(new Portfolio(List.of(mine)), new Reports(sharedReport()), visits);
        var foreign = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatThrownBy(() -> useCase.markSeen(ReportViewer.of(Fixtures.USER_ID), foreign, Fixtures.NOW))
                .isInstanceOf(HorizonException.class);

        // И ничего не записано: отказ, после которого отметка всё-таки легла, — это тот же дефект,
        // только тише.
        assertThat(visits.lastSeenBy(Fixtures.USER_ID)).isEmpty();
    }

    @Test
    void openingMyOwnDirectionRecordsTheVisit() {
        var visits = new Visits();
        var mine = tracked("моё", UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        var useCase = new RadarDigestUseCase(new Portfolio(List.of(mine)), new Reports(sharedReport()), visits);

        useCase.markSeen(ReportViewer.of(Fixtures.USER_ID), mine.id(), Fixtures.NOW);

        assertThat(visits.lastSeenBy(Fixtures.USER_ID)).containsEntry(mine.id(), Fixtures.NOW);
    }
}
