package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.horizon.trends.domain.report.TopicOccurrence;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.support.Fixtures;

/**
 * The topic search query, executed rather than mocked (BR-A67, BR-A68).
 *
 * <p>Весь смысл этого запроса — в предикате: три соединения, подстрока и граница организации. Выше
 * этого слоя он непроверяем, потому что там репозиторий подменён, а ниже его нет. Ради этого запрос
 * и написан на JPQL вместо рукописного SQL, которым пишется отчёт: рукописный не исполняется ни
 * одним тестом и назван непокрытым в спецификации среднего слоя.
 *
 * <p>Строки отчётов создаются здесь обычным INSERT: проекции неизменяемы намеренно, писать ими
 * нельзя, а поднимать ради подготовки данных весь JDBC-репозиторий с его {@code jsonb} значило бы
 * проверять запись там, где проверяется чтение.
 *
 * <p><b>Чего этот тест не доказывает.</b> Схема здесь строится из сущностей, а проекции отображают
 * лишь часть колонок — значит таблицы в H2 состоят ровно из того, что объявлено в проекции, и
 * опечатка в имени колонки прошла бы здесь незамеченной. Сверено вручную с {@code V1__init.sql}:
 * {@code trend_reports} — id, research_request_id, version, raw_query, normalized_query,
 * generated_at; {@code report_trends} — report_id, rank (smallint), trend_key, title. Автоматически
 * это проверит только {@code ddl-auto=validate} против мигрированной базы, то есть Testcontainers.
 */
class TopicSearchQueriesTest extends PersistenceTestBase {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID BANK = Fixtures.ORGANIZATION_ID;
    private static final UUID OTHER_BANK = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID ME = Fixtures.USER_ID;
    private static final UUID COLLEAGUE = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID STRANGER = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Autowired
    private TopicSearchJpaRepository jpa;

    @Autowired
    private ResearchRequestJpaRepository requests;

    @Autowired
    private EntityManager entities;

    private final ResearchRequestMapper mapper = new ResearchRequestMapper();

    private TopicSearchRepositoryAdapter adapter() {
        return new TopicSearchRepositoryAdapter(jpa);
    }

    private ReportViewer me() {
        return new ReportViewer(ME, BANK, false);
    }

    /** Нейтральный текст: он есть в каждой строке, поэтому совпадать не должен ни с одним запросом. */
    private static final String ANY_DEFINITION = "Определение темы для проверки.";

    private static final String ANY_PROBLEM = "Формулировка проблемы для проверки.";

    /** Отчёт с одной темой: смотрим только на название, тексты нейтральные. */
    private UUID report(UUID userId, UUID organizationId, String direction, String title, Instant generatedAt) {
        return report(userId, organizationId, direction, title, ANY_DEFINITION, ANY_PROBLEM, generatedAt);
    }

    /** Отчёт с одной темой: запрос за пределы трёх текстовых полей и границ доступа не смотрит. */
    private UUID report(
            UUID userId,
            UUID organizationId,
            String direction,
            String title,
            String definition,
            String problemStatement,
            Instant generatedAt) {
        var request = ResearchRequest.submit(
                new RequesterRef(userId, organizationId),
                TechnologyDomainQuery.of(direction),
                Fixtures.parameters(),
                null,
                Duration.ofMinutes(10),
                NOW);
        requests.saveAndFlush(mapper.toEntity(request, null));

        var reportId = UUID.randomUUID();
        entities.createNativeQuery(
                        """
                        INSERT INTO trend_reports (
                            id, research_request_id, version, raw_query, normalized_query, generated_at)
                        VALUES (?, ?, 1, ?, ?, ?)
                        """)
                .setParameter(1, reportId)
                .setParameter(2, request.id().value())
                .setParameter(3, direction)
                .setParameter(4, request.query().normalized())
                .setParameter(5, generatedAt)
                .executeUpdate();

        entities.createNativeQuery(
                        """
                        INSERT INTO report_trends (
                            report_id, rank, trend_key, title, definition, problem_statement)
                        VALUES (?, 1, ?, ?, ?, ?)
                        """)
                .setParameter(1, reportId)
                .setParameter(2, title.toLowerCase(Locale.ROOT))
                .setParameter(3, title)
                .setParameter(4, definition)
                .setParameter(5, problemStatement)
                .executeUpdate();
        entities.clear();
        return reportId;
    }

    @Test
    void aTopicIsFoundByAFragmentOfItsTitle() {
        report(ME, BANK, "скоринг", "Federated learning", NOW);

        assertThat(adapter().findOccurrences("derated lear", me(), 100))
                .extracting(TopicOccurrence::title)
                .containsExactly("Federated learning");
    }

    @Test
    void theSearchIgnoresCase() {
        report(ME, BANK, "скоринг", "Federated Learning", NOW);

        assertThat(adapter().findOccurrences("FEDERATED", me(), 100)).hasSize(1);
    }

    @Test
    void aColleaguesReportIsFoundAndAStrangersIsNot() {
        // BR-A68 и граница, ради которой предикат вообще существует. Обе строки создаются нарочно:
        // утверждение «нашлась одна» не отличает «нашлась своя» от «нашлась любая», и падать ему
        // было бы не на чем.
        report(COLLEAGUE, BANK, "скоринг", "Federated learning", NOW);
        report(STRANGER, OTHER_BANK, "чужое направление", "Federated learning", NOW);

        var found = adapter().findOccurrences("federated", me(), 100);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).query()).isEqualTo("скоринг");
    }

    @Test
    void anAdministratorSeesAcrossOrganisations() {
        report(STRANGER, OTHER_BANK, "чужое направление", "Federated learning", NOW);

        assertThat(adapter().findOccurrences("federated", new ReportViewer(ME, BANK, true), 100))
                .hasSize(1);
    }

    @Test
    void theRowLimitAppliesAfterTheAccessFilterAndNotBeforeIt() {
        // Иначе одна крупная чужая организация вытесняла бы своё из выдачи: предел выбрал бы её
        // строки, а фильтр потом вернул бы пустоту — «ничего не нашлось» при существующем ответе.
        for (int i = 0; i < 5; i++) {
            report(STRANGER, OTHER_BANK, "чужое " + i, "Federated learning", NOW.plusSeconds(i + 10));
        }
        report(ME, BANK, "скоринг", "Federated learning", NOW);

        var found = adapter().findOccurrences("federated", me(), 3);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).query()).isEqualTo("скоринг");
    }

    @Test
    void aPercentSignIsSearchedForLiterallyAndNotAsAWildcard() {
        // Процент, набранный аналитиком, — символ, который он ищет, а не шаблон, который он задаёт.
        //
        // Запрос подобран так, чтобы различать эти два случая. В названии между «100%» и «капитала»
        // стоит пробел, а в запросе его нет: как подстрока «100%капитала» не совпадает ни с чем, а
        // как шаблон — «100», что угодно, «капитала» — совпала бы. Пустота здесь и есть
        // доказательство. Первая попытка этого теста искала просто «100%» и оставалась зелёной с
        // выключенным экранированием: совпадало и так и так.
        report(ME, BANK, "капитал", "Достаточность 100% капитала", NOW);

        assertThat(adapter().findOccurrences("100%капитала", me(), 100)).isEmpty();
    }

    @Test
    void anUnderscoreIsSearchedForLiterallyAndNotAsAnySingleCharacter() {
        // Та же логика: в названии пробел, в запросе подчёркивание. Как шаблон оно совпало бы с
        // пробелом, как символ — не совпадает ни с чем.
        report(ME, BANK, "модели", "Модель risk score", NOW);

        assertThat(adapter().findOccurrences("risk_score", me(), 100)).isEmpty();
    }

    @Test
    void anUnderscoreStillFindsATitleThatReallyContainsIt() {
        // Обратная половина: экранирование не должно превратить поиск в «ничего не находит».
        report(ME, BANK, "модели", "Модель risk_score", NOW);

        assertThat(adapter().findOccurrences("risk_score", me(), 100))
                .extracting(TopicOccurrence::title)
                .containsExactly("Модель risk_score");
    }

    @Test
    void anOccurrenceCarriesEverythingNeededToCheckIt() {
        // BR-A69: ответ «мы про это писали» проверяем только тогда, когда назван отчёт, его версия,
        // место темы и дата. Без них это утверждение, которое не на чем опровергнуть.
        var reportId = report(ME, BANK, "скоринг", "Federated learning", NOW);

        var occurrence = adapter().findOccurrences("federated", me(), 100).get(0);

        assertThat(occurrence.reportId()).isEqualTo(reportId);
        assertThat(occurrence.version()).isOne();
        assertThat(occurrence.rank()).isOne();
        assertThat(occurrence.query()).isEqualTo("скоринг");
        assertThat(occurrence.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void aTopicIsFoundByAWordThatOnlyItsProblemStatementContains() {
        // Ровно жалоба аналитика про уран. Название темы — извлечённый из корпуса термин, и
        // искомое слово в него не попадает: оно стоит в процитированной формулировке проблемы.
        // Поиск, смотрящий только в название, отвечал «ничего нет» на вопрос, ответ на который
        // лежал в соседней колонке той же строки.
        report(
                ME,
                BANK,
                "атомная энергетика",
                "Small modular reactor",
                ANY_DEFINITION,
                "Deployment is limited by the supply of high-assay low-enriched uranium fuel.",
                NOW);

        assertThat(adapter().findOccurrences("uranium", me(), 100))
                .extracting(TopicOccurrence::title)
                .containsExactly("Small modular reactor");
    }

    @Test
    void aTopicIsFoundByAWordThatOnlyItsDefinitionContains() {
        report(
                ME,
                BANK,
                "энергетика",
                "Perovskite tandem",
                "A photovoltaic stack that pairs a perovskite absorber with a silicon cell.",
                ANY_PROBLEM,
                NOW);

        assertThat(adapter().findOccurrences("photovoltaic", me(), 100))
                .extracting(TopicOccurrence::title)
                .containsExactly("Perovskite tandem");
    }

    @Test
    void wordsOfTheQueryMatchInAnyOrderAndAcrossDifferentFields() {
        // «Уран» в проблеме, «reactor» в названии, порядок слов свой. Сцепленная в одну подстроку
        // фраза не совпала бы ни с чем — а вопрос при этом задан осмысленный.
        report(
                ME,
                BANK,
                "атомная энергетика",
                "Small modular reactor",
                ANY_DEFINITION,
                "Deployment is limited by the supply of high-assay low-enriched uranium fuel.",
                NOW);

        assertThat(adapter().findOccurrences("uranium reactor", me(), 100)).hasSize(1);
        assertThat(adapter().findOccurrences("reactor uranium", me(), 100)).hasSize(1);
    }

    @Test
    void everyWordOfTheQueryHasToMatchSomewhere() {
        // Иначе расширение области поиска превратилось бы в размытие выдачи: одно совпавшее слово
        // из трёх — это не ответ на заданный вопрос.
        report(
                ME,
                BANK,
                "атомная энергетика",
                "Small modular reactor",
                ANY_DEFINITION,
                "Deployment is limited by the supply of high-assay low-enriched uranium fuel.",
                NOW);

        assertThat(adapter().findOccurrences("uranium photovoltaic", me(), 100)).isEmpty();
    }

    @Test
    void aTopicThatMatchesNothingReturnsNothing() {
        report(ME, BANK, "скоринг", "Federated learning", NOW);

        assertThat(adapter().findOccurrences("квантовые", me(), 100)).isEmpty();
    }
}
