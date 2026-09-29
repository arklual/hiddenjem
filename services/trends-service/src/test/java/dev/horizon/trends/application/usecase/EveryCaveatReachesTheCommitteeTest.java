package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.Exclusion;
import dev.horizon.trends.domain.report.ExplanationItem;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.TrendLocalization;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.support.Fixtures;

/**
 * Ни одно поле охвата не остаётся только на экране.
 *
 * <p>Правило написано по следу трёх одинаковых дефектов подряд. Оговорка «корпус обрезан» доезжала
 * до записки и не доезжала до экрана; диапазон устойчивости — наоборот, до экрана и не до записки;
 * число проанализированных документов было на экране и отсутствовало и в записке, и в таблице. Один
 * и тот же класс ошибки, три разных поля, и каждый раз он находился случайно.
 *
 * <p>Цена ошибки несимметрична. Записку читает комитет — люди, которые продукта не открывали и
 * переспросить не могут. Поле, потерянное по дороге к ним, не выглядит потерянным: документ цел,
 * связен и убедителен ровно настолько, насколько был бы с этим полем.
 *
 * <p>Поэтому проверка структурная и с явным списком исключений: новое поле в {@link Coverage}
 * заставляет автора решить, попадает ли оно в записку, — в тот же день, а не тогда, когда его
 * отсутствие заметит читатель.
 */
class EveryCaveatReachesTheCommitteeTest {

    private final ExportReportUseCase export =
            new ExportReportUseCase(new FeatureGate(new FeatureFlags(new MockEnvironment())));

    /**
     * Поле охвата → строка, по которой видно, что оно доехало.
     *
     * <p>Значения в образце подобраны так, чтобы каждое поле было отличимо от умолчания: ноль
     * документов и пустой список источников прошли бы проверку, ничего не проверив.
     */
    private static final Map<String, String> EXPECTED_IN_BRIEFING = new LinkedHashMap<>();

    static {
        EXPECTED_IN_BRIEFING.put("documentsAnalyzed", "Проанализировано документов: 1244");
        EXPECTED_IN_BRIEFING.put("candidatesEvaluated", "оценено кандидатов: 340");
        EXPECTED_IN_BRIEFING.put("sourcesUsed", "arxiv");
        EXPECTED_IN_BRIEFING.put("unavailableSources", "uspto");
        EXPECTED_IN_BRIEFING.put("partial", "Корпус собран не полностью: да");
        EXPECTED_IN_BRIEFING.put("directionRecognized", "Направление не распознано");
        EXPECTED_IN_BRIEFING.put("directionSuggestions", "квантовые вычисления");
        EXPECTED_IN_BRIEFING.put("suppressedByAnalyst", "Скрыто аналитиком как «не технология»: 2");
        EXPECTED_IN_BRIEFING.put("corpusTruncated", "Корпус ограничен пределом профиля");
        EXPECTED_IN_BRIEFING.put("windowFrom", "2019-03-01");
        EXPECTED_IN_BRIEFING.put("windowTo", "2026-03-01");
        // Логика исключения — прямое требование ТЗ, и комитет читает записку, а не экран:
        // «должна быть продемонстрирована логика исключения зрелых трендов, массово внедренных
        // технологий, отраслевых стандартов, маркетингового хайпа и информационного шума».
        EXPECTED_IN_BRIEFING.put("exclusions", "## Что не попало в отчёт и почему");
    }

    /** Поля, которых в записке нет намеренно. Пустой список — это тоже решение, и оно записано. */
    private static final Set<String> DELIBERATELY_ABSENT = Set.of();

    /**
     * Поле темы → строка, по которой видно, что оно доехало до записки.
     *
     * <p>Правило то же, что для охвата, и по той же причине: величина, дошедшая до экрана и не
     * дошедшая до записки, — уже четвёртый случай одного класса. Свежий пример — доля направления:
     * она решает, попадёт ли тема в отчёт, и до сих пор её видел только движок.
     */
    private static final Map<String, String> TREND_FIELDS_IN_BRIEFING = new LinkedHashMap<>();

    static {
        TREND_FIELDS_IN_BRIEFING.put("rank", "### 1.");
        TREND_FIELDS_IN_BRIEFING.put("title", "Тема alpha");
        TREND_FIELDS_IN_BRIEFING.put("definition", "Определение");
        TREND_FIELDS_IN_BRIEFING.put("motivation", "Проблема — цитаты:");
        // Подпись — по сработавшему правилу §8, а не общая: фикстура держится на академической
        // группе, и записка обязана назвать её именно так. Прежнее «Пример внедрения» одинаково
        // подписывало патент и препринт, то есть утверждало внедрение в 61 случае из 90.
        TREND_FIELDS_IN_BRIEFING.put("caseExample", "Академическая группа");
        TREND_FIELDS_IN_BRIEFING.put("assessment", "- Балл: ");
        TREND_FIELDS_IN_BRIEFING.put("lifecycleStage", "Стадия: ");
        TREND_FIELDS_IN_BRIEFING.put("firstMentionYear", "первое достоверное упоминание: 2022");
        TREND_FIELDS_IN_BRIEFING.put("totalDocuments", "документов: 48");
        TREND_FIELDS_IN_BRIEFING.put("evidence", "Источники:");
        TREND_FIELDS_IN_BRIEFING.put("explanation", "- Почему такая уверенность: 82 %");
        TREND_FIELDS_IN_BRIEFING.put("directionShare", "К направлению отнесены 7 из 48");
        // Отметка о доверенности и русское название — оба из финального ТЗ. Первая заменяет
        // удаление темы («либо сопровождаться отметкой о пониженной доверенности»), второе
        // выполняет требование «вся аналитическая выдача на русском языке» с сохранением
        // оригинального названия.
        TREND_FIELDS_IN_BRIEFING.put(
                "lowCredibilityOnly", "Все источники темы низкой доверенности");
        TREND_FIELDS_IN_BRIEFING.put("localization", "Оригинальное название: ");
        // Предложение-тренд — часть русского слоя, и записка обязана его нести.
        TREND_FIELDS_IN_BRIEFING.put("localization.statement", "Тренд: Команды начинают делать");
    }

    /**
     * Поля темы, которых в записке нет намеренно, — с причиной у каждого.
     *
     * <p>Причина обязательна: список без неё превращается в место, куда сваливают всё неудобное.
     */
    private static final Map<String, String> TREND_FIELDS_DELIBERATELY_ABSENT = Map.of(
            "trendKey",
            "машинный идентификатор: человеку он ничего не сообщает, а место занимает",
            "burst",
            "всплеск виден в самом балле и в его разборе; отдельной строкой это удвоение",
            "timeline",
            "ряд по годам — таблица, а записка её не воспроизводит: для этого есть выгрузка CSV");

    private TrendReport reportWithEverySignal() {
        var coverage = new Coverage(
                1244,
                340,
                List.of("arxiv", "openalex"),
                List.of("uspto"),
                true,
                false,
                List.of("квантовые вычисления"),
                2,
                true,
                List.of(new Exclusion(
                        "lifecycle_maturing",
                        "зрелая стадия жизненного цикла: рост прекратился или ушёл в патенты",
                        7,
                        List.of("convolutional neural network"))),
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1));
        var request = Fixtures.assemblingRequest();
        var source = Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a", "b"), 50.0);
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                request.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                coverage,
                source.trends(),
                source.trends().size(),
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    /** Отчёт из двух тем: у первой доля слабая (7 из 48), у второй — обычная (5 из 7). */
    private TrendReport reportWithAWeakTopic() {
        var source = reportWithEverySignal();
        var trends = List.of(
                withShare(source.trends().get(0), 1, "alpha", 48, 7.0 / 48.0),
                withShare(source.trends().get(Math.min(1, source.trends().size() - 1)), 2, "beta", 7, 0.71));
        return TrendReport.create(
                Fixtures.assemblingRequest().id(),
                Fixtures.requester(),
                source.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                source.coverage(),
                trends,
                trends.size(),
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    private static RankedTrend withShare(RankedTrend source, int rank, String key, int documents, double share) {
        return new RankedTrend(
                rank,
                key,
                "Тема " + key,
                "Определение",
                source.motivation(),
                source.caseExample(),
                source.assessment(),
                source.lifecycleStage(),
                2022,
                documents,
                source.burst(),
                source.timeline(),
                source.evidence(),
                share,
                true,
                // Определение намеренно оставлено непереведённым: проверка требует, чтобы в
                // записке нашлись **оба** поля — и оригинальное определение, и русское название.
                // Заполнив здесь и то и другое, фикстура скрыла бы потерю оригинала: русская
                // строка заменяет его, а не дополняет, и убедиться в этом можно только оставив
                // одно из полей пустым.
                new TrendLocalization(
                        "Русское название " + key,
                        "horizon-qwen3.5-4b",
                        "GENERATIVE",
                        null,
                        null,
                        null,
                        List.of(),
                        "Helsinki-NLP/opus-mt-en-ru",
                        "MACHINE",
                        "Команды начинают делать " + key + " вместо прежнего подхода",
                        "gpt-test"),
                List.of(new ExplanationItem("Почему такая уверенность", "82 %: три независимых источника")));
    }

    @Test
    @DisplayName("каждое поле охвата названо в записке — или объявлено ненужным читателю")
    void everyCoverageFieldIsEitherPrintedOrDeliberatelyOmitted() {
        Set<String> undecided = new LinkedHashSet<>();
        for (RecordComponent component : Coverage.class.getRecordComponents()) {
            if (!EXPECTED_IN_BRIEFING.containsKey(component.getName())
                    && !DELIBERATELY_ABSENT.contains(component.getName())) {
                undecided.add(component.getName());
            }
        }

        assertThat(undecided)
                .as("поля охвата, о которых никто не решил, попадают ли они к комитету")
                .isEmpty();
    }

    @Test
    @DisplayName("объявленное доезжает до записки на самом деле, а не в списке")
    void whatTheListPromisesIsActuallyInTheBriefing() {
        // Без этой проверки список выше — декларация о намерениях: он остался бы зелёным, даже если
        // из записки убрать всё до единой оговорки.
        String briefing = export.toMarkdown(reportWithEverySignal());

        assertThat(briefing).contains(EXPECTED_IN_BRIEFING.values().toArray(new String[0]));
    }

    @Test
    @DisplayName("каждое поле темы названо в записке — или объявлено ненужным читателю")
    void everyTrendFieldIsEitherPrintedOrDeliberatelyOmitted() {
        Set<String> undecided = new LinkedHashSet<>();
        for (RecordComponent component : dev.horizon.trends.domain.report.RankedTrend.class.getRecordComponents()) {
            if (!TREND_FIELDS_IN_BRIEFING.containsKey(component.getName())
                    && !TREND_FIELDS_DELIBERATELY_ABSENT.containsKey(component.getName())) {
                undecided.add(component.getName());
            }
        }

        assertThat(undecided)
                .as("поля темы, о которых никто не решил, попадают ли они к комитету")
                .isEmpty();
    }

    @Test
    @DisplayName("объявленное по теме доезжает до записки на самом деле")
    void whatTheTrendListPromisesIsActuallyInTheBriefing() {
        String briefing = export.toMarkdown(reportWithAWeakTopic());

        assertThat(briefing).contains(TREND_FIELDS_IN_BRIEFING.values().toArray(new String[0]));
    }

    @Test
    @DisplayName("таблица несёт долю направления у каждой темы, а записка — только у слабой")
    void theTableCarriesTheShareForEveryTopicAndTheBriefingOnlyForWeakOnes() {
        // Разные читатели и разные способы чтения. Таблицу фильтруют и сортируют — там пропуск
        // означал бы «не измеряли». Записку читают подряд, и строка под каждой темой была бы шумом.
        String csv = export.toCsv(reportWithAWeakTopic());
        String briefing = export.toMarkdown(reportWithAWeakTopic());

        assertThat(csv).contains("direction_share");
        assertThat(csv).contains(";0,1458;");
        assertThat(csv).contains(";0,7100;");
        assertThat(briefing).contains("К направлению отнесены 7 из 48");
        assertThat(briefing).doesNotContain("К направлению отнесены 5 из 7");
    }

    @Test
    @DisplayName("число проанализированных документов есть и в таблице")
    void theTableCarriesTheDocumentCountToo() {
        // Таблицу открывают в Excel те же люди и с тем же вопросом. Портрет направления это число
        // не несёт — он описывает найденное, а не прочитанное.
        String csv = export.toCsv(reportWithEverySignal());

        assertThat(csv).contains("проанализировано документов;1244");
    }
}
