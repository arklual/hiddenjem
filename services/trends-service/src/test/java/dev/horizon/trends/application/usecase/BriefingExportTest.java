package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.TimelinePoint;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.support.Fixtures;

/**
 * The briefing document (BR-A73…BR-A78).
 *
 * <p>Записка — единственный формат, который читает человек целиком и переносит в решение. Всё, что
 * проверяется ниже, — это ровно то, что теряется при ручном переносе и ради чего документ вообще
 * собирается машиной: оговорки выше содержания, ссылка у каждой темы, отсутствие личного и
 * одинаковые байты при повторной выгрузке.
 */
class BriefingExportTest {

    private final ExportReportUseCase export =
            new ExportReportUseCase(new FeatureGate(new FeatureFlags(new MockEnvironment())));
    private final dev.horizon.trends.domain.research.ResearchRequest request = Fixtures.assemblingRequest();

    private String briefing() {
        return export.toMarkdown(Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a", "b", "c"), 50.0));
    }

    private static Evidence evidence(String title, String url, String doi) {
        return new Evidence(
                "arxiv",
                SourceClass.PREPRINT,
                "2403.0001",
                title,
                "Иванов И.И.",
                "МФТИ",
                "RU",
                LocalDate.of(2025, 6, 1),
                url,
                doi,
                42,
                0.87,
                "фрагмент");
    }

    /** Отчёт с одной темой, у которой заданы название и доказательства. */
    private TrendReport reportWith(String title, List<Evidence> evidence) {
        var trend = new RankedTrend(
                1,
                "key",
                title,
                "Определение",
                new Motivation("Проблема", "Польза", List.of(new Motivation.Attribution("problem", 0, "Проблема"))),
                new CaseExample(
                        "МФТИ",
                        CaseExample.OrganizationType.UNIVERSITY,
                        "RU",
                        "Прототип",
                        0,
                        CaseExample.Basis.ACADEMIC_GROUP),
                new EmergenceAssessment(72.5, 0.81, false, Fixtures.indicators()),
                LifecycleStage.EMERGING,
                2022,
                57,
                null,
                List.of(new TimelinePoint("2024", 31, 0.7, 0.5)),
                evidence);
        return withTrends(List.of(trend));
    }

    private TrendReport withTrends(List<RankedTrend> trends) {
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                request.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                Fixtures.coverage(),
                trends,
                trends.size(),
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    @Test
    void theCaveatsStandAboveTheTopicsAndNotBelowThem() {
        // P2 и весь смысл формата. Оговорка после пятнадцати тем — это оговорка, отброшенная при
        // копировании первой половины документа: её увидит только тот, кому она не нужна.
        String briefing = briefing();

        int caveats = briefing.indexOf("## Оговорки");
        int topics = briefing.indexOf("## Темы");
        assertThat(caveats).isNotNegative();
        assertThat(topics).isNotNegative();
        assertThat(caveats).isLessThan(topics);
    }

    @Test
    void theBriefingCarriesTheSuggestedWording() {
        // Записку читают отдельно от интерфейса, и ссылку в ней не нажмёшь — но формулировка,
        // которую система знает, переносится в следующий запрос вручную. Без неё оговорка
        // сообщает о тупике и не показывает выхода.
        //
        // Проверяется фраза подсказки целиком, а не одно слово. Первая версия проверяла слово — и
        // проходила при полностью удалённой подсказке, потому что то же слово стоит в запросе
        // отчёта. Подсказка здесь намеренно отличается от запроса по той же причине.
        String briefing = export.toMarkdown(Fixtures.reportWithCoverage(request, unrecognisedDirection()));

        assertThat(briefing).contains("Возможно, вы имели в виду: квантовая криптография.");
    }

    @Test
    void anUnrecognisedDirectionIsCalledOutAboveTheTopics() {
        // Сильнейшая из оговорок и единственная, обесценивающая весь документ: движок не соотнёс
        // направление со словарём предметных кодов корпуса, и список тем не зависит от заданного
        // вопроса. Строкой в перечне такое не подаётся — записка ходит по почте отдельно от
        // интерфейса, и оговорку, прочитанную после пятнадцати обоснованных тем, читают уже после
        // решения.
        String briefing = export.toMarkdown(Fixtures.reportWithCoverage(request, unrecognisedDirection()));

        int warning = briefing.indexOf("Направление не распознано");
        int topics = briefing.indexOf("## Темы");
        assertThat(warning).as("предупреждение о нераспознанном направлении").isNotNegative();
        assertThat(warning).isLessThan(topics);
    }

    @Test
    void arecognisedDirectionSaysNothingAlarming() {
        // Обратная ошибка не менее вредна: оговорка, срабатывающая не по делу, обесценивает те,
        // где она по делу.
        assertThat(briefing()).doesNotContain("Направление не распознано");
    }

    private static Coverage unrecognisedDirection() {
        Coverage recognised = Fixtures.coverage();
        return new Coverage(
                recognised.documentsAnalyzed(),
                recognised.candidatesEvaluated(),
                recognised.sourcesUsed(),
                recognised.unavailableSources(),
                recognised.partial(),
                false,
                List.of("квантовая криптография"),
                0,
                false,
                recognised.windowFrom(),
                recognised.windowTo());
    }

    @Test
    @DisplayName("шапка называет линейку целиком: версию, свёртку и профиль весов")
    void theHeaderNamesTheWholeRuler() {
        // Балл не читается без того, чем он свёрнут: `MIN_BOUND` ограничен слабейшим индикатором,
        // взвешенное среднее — нет, а профиль решает, какой индикатор весит сколько. С тех пор как
        // профиль выбирается на форме поиска, две записки по одному направлению могут стоять на
        // разных весах при одинаковой строке «методология em-1.0.0».
        String briefing = briefing();

        assertThat(briefing).contains("методология em-1.0.0");
        assertThat(briefing).contains("свёртка WEIGHTED_GEOMETRIC");
        assertThat(briefing)
                .contains("профиль весов " + Fixtures.PROFILE_ID.toString().substring(0, 8));
    }

    @Test
    @DisplayName("идентификатор профиля не печатается целиком: комитету нужна сравнимость, а не UUID")
    void theProfileIsShortenedForAHumanDocument() {
        assertThat(briefing()).doesNotContain(Fixtures.PROFILE_ID.toString());
    }

    @Test
    void motivationIsPresentedAsAQuotationNotAsPlatformProse() {
        // Формулировка приходит дословно из источника и часто на языке оригинала. Поданная обычным
        // пунктом, она читается как утверждение платформы на ломаном языке; в кавычках — как то,
        // что действительно написано в первоисточнике. Разница в двух символах и в том, доверяет
        // ли комитет документу.
        String briefing = briefing();

        // Формулировка склеена из предложений разных статей — по эталонному корпусу 166 из 180, —
        // поэтому каждое предложение печатается отдельной цитатой со своим источником. Слитый
        // абзац читался бы как один довод одной работы, которого не делал никто.
        assertThat(briefing).contains("- Проблема — цитаты:");
        assertThat(briefing).contains("    - «Проблема»");

        // Запасной путь: у пользы предложения не записаны, и абзац печатается целиком, как раньше.
        // Разбивать его здесь по точке нельзя — она стоит и внутри «e.g.», и в «1.8x».
        assertThat(briefing).contains("- Польза — цитата: «");
    }

    @Test
    void theQuotingRuleTravelsWithTheDocument() {
        // Записку читают отдельно от интерфейса и без документации. Правило, объясняющее язык
        // цитат, обязано ехать вместе с ней, иначе читатель видит недоделку там, где на самом деле
        // прослеживаемость до первоисточника.
        String briefing = briefing();

        int rule = briefing.indexOf("дословные цитаты из источников");
        int topics = briefing.indexOf("## Темы");
        assertThat(rule).as("правило цитирования").isNotNegative();
        assertThat(rule).isLessThan(topics);
    }

    @Test
    void theCaveatsAreWrittenOutEvenWhenThereIsNothingAlarmingToSay() {
        // «Недоступных источников нет» — утверждение об анализе. Его отсутствие читалось бы как
        // «не проверяли», а это разные вещи.
        String briefing = briefing();

        assertThat(briefing).contains("Недоступные источники: нет");
        assertThat(briefing).contains("Корпус собран не полностью: нет");
    }

    @Test
    void truncationIsSaidOutLoudInBothDirections() {
        // Отчёт, у которого тем меньше запрошенного, усечён по построению; отчёт, взявший ровно
        // столько, сколько просили, — нет. Обе стороны проверяются, иначе утверждение «сказано» не
        // отличалось бы от «написано всегда одно и то же».
        var full = withTrends(List.of(Fixtures.trend(1, "a")));
        var cut = Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a"), 50.0);

        // Формулировка изменена, а намерение теста сохранено. Прежняя строка «Список усечён: да»
        // означала обратное тому, что говорила: не «часть списка отрезана», а «методология нашла
        // меньше тем, чем запрошено». Читатель записки понимал её наоборот.
        assertThat(export.toMarkdown(full)).contains("Тем найдено столько, сколько запрошено");
        assertThat(export.toMarkdown(cut)).contains("Тем найдено меньше запрошенных");
        assertThat(export.toMarkdown(cut)).contains("список не обрезался");
    }

    @Test
    void anUnavailableSourceIsNamedInTheCaveats() {
        var report = Fixtures.reportWithCoverage(
                request, Fixtures.coverageWithSources(List.of("arxiv"), List.of("uspto")));

        assertThat(export.toMarkdown(report)).contains("Недоступные источники: uspto");
    }

    @Test
    void everyTopicCarriesACheckableReference() {
        // BR-A75. Тема без ссылки в записке читается как утверждение без основания; отчёт без
        // доказательств не публикуется вовсе (J2), и документ обязан это свойство сохранить.
        String briefing = briefing();

        int topics = briefing.split("### ", -1).length - 1;
        int links = briefing.split("\\]\\(https://", -1).length - 1;
        assertThat(topics).isEqualTo(3);
        assertThat(links).isGreaterThanOrEqualTo(topics);
    }

    @Test
    void aDoiIsPreferredToALinkBecauseItSurvivesThePublisherMoving() {
        var briefing = export.toMarkdown(
                reportWith("Тема", List.of(evidence("Статья", "https://arxiv.org/abs/1", "10.1000/1"))));

        assertThat(briefing).contains("(https://doi.org/10.1000/1)");
        assertThat(briefing).doesNotContain("(https://arxiv.org/abs/1)");
    }

    // Случая «ни DOI, ни ссылки» не существует: домен требует непустой url у каждого доказательства
    // (Evidence: «evidence.url must not be blank»). Ветка без ссылки достижима только через адрес не
    // веб-схемы — он и проверяется ниже. Написано здесь, а не оставлено на догадку: тест, который я
    // сначала попробовал написать на несуществующий случай, был бы проверкой мёртвой ветки.

    @Test
    void aLinkThatIsNotTheWebIsNotMadeClickable() {
        // Адрес приходит из внешнего источника, а документ открывают в редакторе, где ссылка
        // кликабельна. javascript: в записке комитету — не ссылка на доказательство.
        var briefing = export.toMarkdown(reportWith("Тема", List.of(evidence("Статья", "javascript:alert(1)", null))));

        assertThat(briefing).doesNotContain("(javascript:");
        assertThat(briefing).contains("Статья — arxiv:2403.0001");
    }

    @Test
    void aTitleCannotForgeALinkOrOpenAHeading() {
        // Название приходит из внешних данных и попадает в разметку. Без экранирования оно
        // подделало бы ссылку, которой автор отчёта не писал, и разорвало бы структуру документа.
        var briefing = export.toMarkdown(reportWith(
                "[смотрите здесь](https://evil.example) # заголовок",
                List.of(evidence("Статья", "https://arxiv.org/abs/1", null))));

        assertThat(briefing).doesNotContain("[смотрите здесь](https://evil.example)");
        assertThat(briefing).contains("\\[смотрите здесь\\]");
        // И заголовком тема не становится: решётка внутри строки безобидна, опасна ведущая.
        assertThat(briefing).doesNotContain("\n# заголовок");
    }

    @Test
    void aNewlineInsideATitleDoesNotBreakTheDocumentApart() {
        var briefing = export.toMarkdown(reportWith(
                "Первая строка\n## Поддельный заголовок", List.of(evidence("С", "https://a.example", null))));

        assertThat(briefing).doesNotContain("\n## Поддельный заголовок");
        assertThat(briefing).contains("Первая строка ## Поддельный заголовок");
    }

    @Test
    void twoExportsOfTheSameReportAreIdentical() {
        // BR-A76. Проверяется исполнением, а не рассуждением: обращение к текущему времени или к
        // неупорядоченной коллекции не видно при чтении кода, но видно здесь.
        var report = Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a", "b"), 50.0);

        assertThat(export.toMarkdown(report)).isEqualTo(export.toMarkdown(report));
    }

    @Test
    void theDocumentIsDatedByTheAnalysisAndNotByTheDownload() {
        // BR-A77. Дата выгрузки сообщала бы о свежести данных то, чего в них нет, и делала бы два
        // скачивания одного отчёта разными файлами.
        assertThat(briefing()).contains(Fixtures.NOW.plusSeconds(90).toString());
    }

    @Test
    void theScoreNeverStandsWithoutItsConfidence() {
        // Число без оговорки — ровно то, ради чего этот формат написан иначе, чем таблица.
        String briefing = briefing();

        for (String line : briefing.split("\n")) {
            if (line.startsWith("- Балл:")) {
                assertThat(line).contains("уверенность:");
            }
        }
        assertThat(briefing).contains("- Балл:");
    }

    @Test
    void aThinEvidenceBaseIsSaidNextToTheScoreAndNotOnlyInTheCaveats() {
        var report = Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a"), 50.0);
        var thin = new RankedTrend(
                report.trends().get(0).rank(),
                report.trends().get(0).trendKey(),
                report.trends().get(0).title(),
                report.trends().get(0).definition(),
                report.trends().get(0).motivation(),
                report.trends().get(0).caseExample(),
                new EmergenceAssessment(50.0, 0.3, true, Fixtures.indicators()),
                report.trends().get(0).lifecycleStage(),
                report.trends().get(0).firstMentionYear(),
                report.trends().get(0).totalDocuments(),
                report.trends().get(0).burst(),
                report.trends().get(0).timeline(),
                report.trends().get(0).evidence());
        assertThat(export.toMarkdown(withTrends(List.of(thin)))).contains("тонкая доказательная база");
        // И контраст: у плотной темы этой пометки нет, иначе тест не отличал бы «помечает нужное»
        // от «помечает всегда».
        assertThat(briefing()).doesNotContain("тонкая доказательная база");
    }

    @Test
    void theTopicsFollowTheRankingAndNothingElse() {
        String briefing =
                export.toMarkdown(Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a", "b", "c"), 50.0));

        assertThat(briefing.indexOf("### 1.")).isLessThan(briefing.indexOf("### 2."));
        assertThat(briefing.indexOf("### 2.")).isLessThan(briefing.indexOf("### 3."));
    }

    @Test
    void rawHtmlInATitleIsNotLeftAsMarkup() {
        // Markdown пропускает сырой HTML, поэтому запрет небезопасных схем в поле ссылки обходился
        // бы тегом в соседнем поле: <a href="javascript:…"> — та же кликабельная ссылка, просто
        // через другую дверь. Защита, закрывающая одну из двух, выглядит полной и таковой не была.
        var briefing = export.toMarkdown(reportWith(
                "<a href=\"javascript:alert(1)\">Смотрите здесь</a>",
                List.of(evidence("Статья", "https://arxiv.org/abs/1", null))));

        // Проверяется не отсутствие подстроки — экранированный «\\<a href=» её как раз содержит, — а
        // то, что неэкранированной угловой скобки в документе не осталось ни одной.
        assertThat(briefing.replace("\\<", "")).doesNotContain("<a href=");
        assertThat(briefing).contains("\\<a href=");
    }

    @Test
    void aDefinitionCannotForgeAFactLine() {
        // Определение печатается с начала строки. Ведущий дефис сделал бы из него пункт, неотличимый
        // от настоящих, — включая «- Балл: 99,9», то есть число без происхождения внутри блока темы.
        var trend = new RankedTrend(
                1,
                "key",
                "Тема",
                "- Балл: 99,9, уверенность: 1,00",
                new Motivation("Проблема", "Польза", List.of(new Motivation.Attribution("problem", 0, "Проблема"))),
                null,
                new EmergenceAssessment(10.0, 0.5, false, Fixtures.indicators()),
                LifecycleStage.EMERGING,
                2022,
                57,
                null,
                List.of(new TimelinePoint("2024", 31, 0.7, 0.5)),
                List.of(evidence("Статья", "https://arxiv.org/abs/1", null)));

        var briefing = export.toMarkdown(withTrends(List.of(trend)));

        assertThat(briefing).doesNotContain("\n- Балл: 99,9");
        assertThat(briefing).contains("\\- Балл: 99,9");
        // И настоящая строка балла на месте — иначе тест не отличал бы «подделка обезврежена» от
        // «строки баллов больше нет».
        assertThat(briefing).contains("\n- Балл: 10,0, уверенность: 0,50");
    }

    @Test
    void aSpaceInsideADoiDoesNotTurnTheLinkIntoPlainText() {
        // По CommonMark адрес с пробелом перестаёт быть адресом: строка отрисовалась бы буквально,
        // вместе с видимыми слэшами, и ссылки у источника не осталось бы — молча.
        var briefing =
                export.toMarkdown(reportWith("Тема", List.of(evidence("Статья", "https://a.example", "10.1000/x y"))));

        assertThat(briefing).contains("(https://doi.org/10.1000/x%20y)");
    }

    @Test
    void everyTopicHasAtLeastOneReferenceOfItsOwn() {
        // Считать ссылки по всему документу и сравнивать с числом тем — недостаточно: тема без ссылок
        // и тема с двумя дают ту же сумму. Проверяется каждый блок отдельно.
        String briefing = briefing();

        var blocks = briefing.split("### ");
        assertThat(blocks).hasSizeGreaterThan(1);
        for (int i = 1; i < blocks.length; i++) {
            assertThat(blocks[i]).as("тема %d несёт хотя бы один источник", i).contains("](https://");
        }
    }

    @Test
    void theCaveatsSurviveTheDirectionPortraitFeatureBeingOff() {
        // У CSV портрет под флагом, и это осознанно: там он оформление. В записке оговорки — это
        // содержание (BR-A74), и выключенный флаг не должен их снимать.
        var off = new MockEnvironment().withProperty("horizon.features.direction-portrait", "false");
        var withoutPortrait = new ExportReportUseCase(new FeatureGate(new FeatureFlags(off)));

        var briefing = withoutPortrait.toMarkdown(Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a"), 50.0));

        assertThat(briefing).contains("## Оговорки");
        assertThat(briefing).contains("На тонкой доказательной базе:");
    }

    @Test
    void aReportWithoutTopicsStillCarriesItsCaveats() {
        // Пустая записка — тоже ответ, и оговорки в ней те же: сказать «тем нет» без «источники были
        // недоступны» значит сказать не то.
        var empty = withTrends(List.of());

        var briefing = export.toMarkdown(empty);

        assertThat(briefing).contains("## Оговорки");
        assertThat(briefing).contains("## Темы");
        assertThat(briefing).doesNotContain("### 1.");
    }

    @Test
    void theBriefingSaysHowManyTopicsTheAnalystHid() {
        // Тема, помеченная как «не технология», убирается до отбора в ТОП-N, и её место занимает
        // следующий кандидат: список выглядит полным, а состав его — следствие данных и чужого
        // суждения разом. Записка, умалчивающая об этом, заявляет большую объективность, чем имеет,
        // и проверить умолчание читателю нечем.
        var text = export.toMarkdown(withSuppressed(2));

        assertThat(text).contains("Скрыто аналитиком");
        assertThat(text).contains("2");
        // Оговорки стоят до тем: сноска в конце отбрасывается при копировании первой половины.
        assertThat(text.indexOf("Скрыто аналитиком")).isLessThan(text.indexOf("## Темы"));
    }

    @Test
    void anUntouchedReportDoesNotCarryTheLine() {
        // «Скрыто: 0» в каждой записке — шум, который приучает не читать оговорки, а они здесь
        // единственная защита от чрезмерного доверия.
        assertThat(briefing()).doesNotContain("Скрыто аналитиком");
    }

    @Test
    void theCsvCarriesTheNumberEvenWhenItIsZero() {
        // Таблицу читает скрипт, а у скрипта отсутствие строки и ноль — разные вещи: первое он
        // трактует как «поле не поддерживается этой версией».
        var csv = export.toCsv(Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a"), 50.0));

        assertThat(csv).contains("скрыто аналитиком");
    }

    /** Тот же отчёт, но с числом скрытых тем в покрытии. */
    private TrendReport withSuppressed(int hidden) {
        var base = Fixtures.coverageWithSources(List.of("arxiv"), List.of());
        var coverage = new Coverage(
                base.documentsAnalyzed(),
                base.candidatesEvaluated(),
                base.sourcesUsed(),
                base.unavailableSources(),
                base.partial(),
                base.directionRecognized(),
                base.directionSuggestions(),
                hidden,
                false,
                base.windowFrom(),
                base.windowTo());
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                request.query(),
                new dev.horizon.trends.domain.report.MethodologyRef(
                        "em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC"),
                Fixtures.SNAPSHOT_ID,
                coverage,
                Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a", "b"), 50.0)
                        .trends(),
                5,
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    @Test
    void aTitleThatLooksLikeAFormulaIsNotExecutedByExcel() {
        // Заголовки тем и названия источников приходят из внешних документов: препринтов, патентов и
        // RSS-лент, публиковать которые может кто угодно. Статья с названием
        // =HYPERLINK("http://…";"Отчёт") превращается в кликабельную ссылку в таблице аналитика, а
        // исторические сценарии с DDE запускали и внешние программы. Файл — тот самый, что несут
        // комитету, и открывают его именно в Excel: ради этого выше стоит BOM.
        var csv = export.toCsv(reportTitled("=HYPERLINK(\"http://evil\")"));

        assertThat(csv).contains("'=HYPERLINK");
        assertThat(csv).doesNotContain(";=HYPERLINK");
    }

    @Test
    void everyDangerousOpeningIsNeutralised() {
        // Список по OWASP, а не по одному замеченному случаю: защита от одного символа даёт ложное
        // чувство закрытого вопроса.
        for (String opening : List.of("=", "+", "-", "@")) {
            assertThat(export.toCsv(reportTitled(opening + "cmd"))).contains("'" + opening + "cmd");
        }
    }

    @Test
    void anOrdinaryTitleIsNotTouched() {
        // Обратная ошибка: апостроф перед каждым значением испортил бы таблицу целиком и приучил бы
        // читателя его игнорировать — включая те случаи, где он стоит по делу.
        var csv = export.toCsv(reportTitled("квантовые сенсоры"));

        assertThat(csv).contains("квантовые сенсоры").doesNotContain("'квантовые сенсоры");
    }

    /** Отчёт с одной темой, заголовок которой задан вызывающим. */
    private TrendReport reportTitled(String title) {
        var base = Fixtures.trend(1, "a");
        var hostile = new dev.horizon.trends.domain.report.RankedTrend(
                base.rank(),
                base.trendKey(),
                title,
                base.definition(),
                base.motivation(),
                base.caseExample(),
                base.assessment(),
                base.lifecycleStage(),
                base.firstMentionYear(),
                base.totalDocuments(),
                base.burst(),
                base.timeline(),
                base.evidence());
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                request.query(),
                new dev.horizon.trends.domain.report.MethodologyRef(
                        "em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC"),
                Fixtures.SNAPSHOT_ID,
                Fixtures.coverageWithSources(List.of("arxiv"), List.of()),
                List.of(hostile),
                5,
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    @Test
    void aDoiWithLineBreaksCannotEscapeItsLink() {
        // Путь через DOI собирает адрес сам — «https://doi.org/» плюс чужая строка — и потому не
        // проходит проверку схемы: та применяется только к готовому адресу. Перевод строки внутри
        // адреса выводит из ссылки: по CommonMark она рвётся, а строки попадают в тело записки как
        // обычный текст. Поддельный заголовок в документе для комитета выглядит как вывод авторов.
        var briefing = export.toMarkdown(reportWith(
                "Тема", List.of(evidence("Статья", "https://example.org/a", "10.1/x\n\n# Поддельный заголовок"))));

        assertThat(briefing).doesNotContain("\n# Поддельный заголовок");
        assertThat(briefing).contains("%0A");
    }

    @Test
    void anOrdinaryDoiStaysReadable() {
        // Обратная ошибка: процентировать всё подряд — значит сделать нечитаемой каждую ссылку.
        var briefing = export.toMarkdown(
                reportWith("Тема", List.of(evidence("Статья", "https://example.org/a", "10.1000/xyz123"))));

        assertThat(briefing).contains("https://doi.org/10.1000/xyz123");
    }

    @Test
    void aBracketInTheUrlCannotCloseTheLink() {
        // Найдено подменой при проверке соседнего правила: кодирование скобки существовало и не
        // проверялось ничем — подмена «перестать кодировать скобку» проходила незамеченной.
        // Круглая скобка в адресе закрывает ссылку раньше времени, и хвост адреса вываливается в
        // текст записки. Такие адреса не экзотика: скобки есть в ссылках Википедии и во многих
        // идентификаторах патентов.
        var briefing =
                export.toMarkdown(reportWith("Тема", List.of(evidence("Статья", "https://example.org/a(b)c", null))));

        assertThat(briefing).contains("%28").contains("%29");
        assertThat(briefing).doesNotContain("a(b)c");
    }

    @Test
    void anIncompleteCorpusIsSaidOutLoudInBothDirections() {
        // Оговорка приходила от движка и терялась: сборщик отчёта её не передавал, а поле с тем же
        // именем в отчёте вычислялось заново и означало другое — «тем меньше запрошенных». Комитет
        // читает записку как обзор области, и «часть литературы не рассматривалась» — то, что он
        // обязан знать. Три причины неполноты (недоступный источник, обрезанный корпус, меньше тем)
        // говорят читателю разное, и путать их нельзя.
        assertThat(export.toMarkdown(withCorpusTruncated(true))).contains("Корпус ограничен пределом профиля");
        assertThat(export.toMarkdown(withCorpusTruncated(false))).contains("Корпус рассмотрен целиком");
    }

    @Test
    void theCsvCarriesTheCorpusLimitToo() {
        assertThat(export.toCsv(withCorpusTruncated(true))).contains("корпус обрезан пределом;true");
    }

    /** Отчёт с заданным флагом обрезки корпуса. */
    private TrendReport withCorpusTruncated(boolean corpusTruncated) {
        var base = Fixtures.coverageWithSources(List.of("arxiv"), List.of());
        var coverage = new Coverage(
                base.documentsAnalyzed(),
                base.candidatesEvaluated(),
                base.sourcesUsed(),
                base.unavailableSources(),
                base.partial(),
                base.directionRecognized(),
                base.directionSuggestions(),
                base.suppressedByAnalyst(),
                corpusTruncated,
                base.windowFrom(),
                base.windowTo());
        return Fixtures.reportWithCoverage(request, coverage);
    }
}
