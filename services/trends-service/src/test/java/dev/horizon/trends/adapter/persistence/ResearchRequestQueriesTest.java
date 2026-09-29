package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.support.Fixtures;

/**
 * Research-request queries, executed rather than mocked (BR-T1, BR-T3).
 *
 * <p>Every predicate here decides money or visibility: whether a colleague's running analysis is
 * joined or paid for twice, whose finished report is reused, whose history is listed. Two of them
 * shipped scoped to the wrong column, and the unit tests could not notice because they answer from a
 * stub that restates the rule instead of running it.
 */
class ResearchRequestQueriesTest extends PersistenceTestBase {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID BANK = Fixtures.ORGANIZATION_ID;
    private static final UUID OTHER_BANK = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID ME = Fixtures.USER_ID;
    private static final UUID COLLEAGUE = UUID.fromString("77777777-7777-4777-8777-777777777777");

    private static final List<String> LIVE = List.of("PENDING", "COLLECTING", "ANALYZING", "ASSEMBLING");

    @Autowired
    private ResearchRequestJpaRepository requests;

    /** Не бин: срез @DataJpaTest поднимает только слой доступа к данным, а маппер и не нуждается в нём. */
    private final ResearchRequestMapper mapper = new ResearchRequestMapper();

    private ResearchRequest save(UUID userId, UUID organizationId, String query, boolean complete) {
        var request = ResearchRequest.submit(
                new RequesterRef(userId, organizationId),
                TechnologyDomainQuery.of(query),
                Fixtures.parameters(),
                null,
                Duration.ofMinutes(10),
                NOW);
        if (complete) {
            request.startCollecting(NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), NOW.plusSeconds(2));
            request.startAssembling(UUID.randomUUID(), NOW.plusSeconds(3));
            request.complete(
                    new dev.horizon.trends.domain.report.TrendReportId(UUID.randomUUID()), 15, NOW.plusSeconds(90));
        }
        requests.saveAndFlush(mapper.toEntity(request, null));
        return request;
    }

    private String discriminator() {
        return Fixtures.parameters().cacheDiscriminator();
    }

    @Test
    void aColleaguesRunningAnalysisIsFound() {
        // BR-A47. Пока поиск шёл по пользователю, разница между «повезло» и «не повезло» измерялась
        // секундами, а платила за неё одна и та же организация.
        var theirs = save(COLLEAGUE, BANK, "квантовые вычисления", false);

        var found = requests.findActive(BANK, "квантовые вычисления", discriminator(), LIVE, PageRequest.of(0, 1));

        assertThat(found)
                .extracting(ResearchRequestEntity::getId)
                .containsExactly(theirs.id().value());
    }

    @Test
    void anotherOrganisationsRunningAnalysisIsNotFound() {
        save(COLLEAGUE, OTHER_BANK, "квантовые вычисления", false);

        assertThat(requests.findActive(BANK, "квантовые вычисления", discriminator(), LIVE, PageRequest.of(0, 1)))
                .isEmpty();
    }

    @Test
    void aFinishedAnalysisIsNotRunning() {
        // Терминальные статусы исключены самим запросом. Ошибка здесь заставила бы завершённый
        // запрос вечно считаться идущим — и второй анализ не запустился бы никогда.
        save(ME, BANK, "квантовые вычисления", true);

        assertThat(requests.findActive(BANK, "квантовые вычисления", discriminator(), LIVE, PageRequest.of(0, 1)))
                .isEmpty();
    }

    @Test
    void theFreshResultIsReusedOnlyInsideTheOrganisation() {
        // BR-A43: кэш обязан совпадать с видимостью. Пока он был общим, коллеге называли отчёт,
        // который ему затем отказывались показать.
        var mine = save(ME, BANK, "искусственный интеллект", true);
        save(COLLEAGUE, OTHER_BANK, "искусственный интеллект", true);

        var found = requests.findFreshCompleted(
                "искусственный интеллект", discriminator(), BANK, NOW, PageRequest.of(0, 10));

        assertThat(found)
                .extracting(ResearchRequestEntity::getId)
                .containsExactly(mine.id().value());
    }

    @Test
    void aStaleResultIsNotReused() {
        save(ME, BANK, "искусственный интеллект", true);

        var found = requests.findFreshCompleted(
                "искусственный интеллект", discriminator(), BANK, NOW.plus(Duration.ofDays(2)), PageRequest.of(0, 10));

        assertThat(found).isEmpty();
    }

    @Test
    void theHistorySpansTheOrganisation() {
        // BR-A52. Пока история искала по пользователю, присоединённый расчёт коллеги исчезал вместе
        // с закрытой вкладкой.
        save(ME, BANK, "моё направление", true);
        save(COLLEAGUE, BANK, "направление коллеги", true);
        save(COLLEAGUE, OTHER_BANK, "чужой банк", true);

        var page = requests.findByOrganizationIdOrderBySubmittedAtDesc(BANK, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void theNarrowedHistoryShowsOnlyTheCallersOwn() {
        save(ME, BANK, "моё направление", true);
        save(COLLEAGUE, BANK, "направление коллеги", true);

        var page = requests.findByUserIdOrderBySubmittedAtDesc(ME, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isOne();
    }

    @Test
    void theStatusFilterNarrowsTheOrganisationsHistory() {
        save(ME, BANK, "завершённое", true);
        save(COLLEAGUE, BANK, "идущее", false);

        var page =
                requests.findByOrganizationIdAndStatusOrderBySubmittedAtDesc(BANK, "COMPLETED", PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isOne();
    }
}
