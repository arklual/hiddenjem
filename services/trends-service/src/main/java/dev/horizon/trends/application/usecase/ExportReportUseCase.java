package dev.horizon.trends.application.usecase;

import java.util.List;

import org.springframework.stereotype.Service;

import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.DirectionPortrait;
import dev.horizon.trends.domain.report.Exclusion;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.StabilitySummary;
import dev.horizon.trends.domain.report.TrendReport;

/**
 * Renders a report as CSV and as a committee-ready document (FR-03.8, BR-A73).
 *
 * <p>One row per trend with evidence collapsed into a single cell: analysts paste this into Excel,
 * where a one-row-per-trend layout is what they can actually sort and filter. JSON export reuses the
 * API representation and needs no separate rendering.
 *
 * <p>The file opens with the direction's portrait — a short key/value block, then a blank line, then
 * the table. This deliberately costs strict CSV parsers their first line, and the trade is made
 * knowingly: the BOM above already declares this format to be the one humans open, machines have the
 * lossless JSON export, and BR-A17 forbids the caveats from being droppable. A CSV carrying only the
 * fifteen rows is exactly the artefact that reaches a committee with the qualifications removed —
 * every number intact and the sentence "three of these rest on two documents each" gone.
 */
@Service
public class ExportReportUseCase {

    private final FeatureGate features;

    public ExportReportUseCase(FeatureGate features) {
        this.features = features;
    }

    /** Доля, ниже которой тема заслуживает вопроса. Половина — не «мало», а «меньшинство». */
    private static final double WEAK_DIRECTION_SHARE = 0.5;

    private static final List<String> HEADERS = List.of(
            "rank",
            // Рядом с рангом, а не в конце строки. Позиционному читателю это стоит сдвига колонок,
            // и цена принята сознательно: CSV здесь объявлен форматом для человека — ради него уже
            // пожертвовали строгими парсерами, поставив BOM, — а машине предназначена выгрузка
            // JSON. Диапазон места, отделённый от места двадцатью колонками, читателю бесполезен.
            "rank_stability_best",
            "rank_stability_worst",
            "direction_share",
            "trend",
            "definition",
            "emergence_score",
            "confidence",
            "low_evidence",
            "lifecycle_stage",
            "first_mention_year",
            "total_documents",
            "problem",
            "benefit",
            "case_organization",
            "case_country",
            // Ради этой колонки таблица и существует рядом с карточкой: имя организации без
            // основания одинаково выглядит и для патента, и для препринта, а это разные
            // утверждения. Фильтр по колонке отделяет 29 тем на патенте от 61 на публикации.
            "case_basis",
            "novelty",
            "growth",
            "diffusion",
            "weakness",
            "coherence",
            "impact",
            "sources");

    public String toCsv(TrendReport report) {
        var sb = new StringBuilder();
        // BOM so Excel on Windows opens UTF-8 Cyrillic correctly — a small detail that decides
        // whether the export is usable at all for the target audience.
        sb.append('﻿');
        if (features.isEnabled(FeatureFlag.DIRECTION_PORTRAIT)) {
            appendPortrait(
                    sb,
                    DirectionPortrait.of(report),
                    StabilitySummary.of(report.trends()),
                    report.coverage().documentsAnalyzed());
            sb.append('\n');
        }
        sb.append(String.join(";", HEADERS)).append('\n');
        for (RankedTrend trend : report.trends()) {
            sb.append(row(trend)).append('\n');
        }
        return sb.toString();
    }

    /**
     * The portrait as key/value rows, ahead of the table.
     *
     * <p>Written out even when a value is zero or a list is empty: "недоступных источников нет" is a
     * statement about the analysis, and its absence would read as "not checked".
     */
    private void appendPortrait(
            StringBuilder sb, DirectionPortrait portrait, StabilitySummary summary, int documentsAnalyzed) {
        var rows = new java.util.ArrayList<List<String>>();
        // Первой строкой — основание всего остального. Портрет направления это число не несёт: он
        // описывает найденное, а не прочитанное. Читателю нужны оба, и «оценено кандидатов: 340»
        // без «проанализировано документов: 1244» не даёт понять, велика ли выборка вообще.
        rows.add(List.of("проанализировано документов", String.valueOf(documentsAnalyzed)));
        rows.add(List.of("тем в отчёте", String.valueOf(portrait.trendsInReport())));
        rows.add(List.of("оценено кандидатов", String.valueOf(portrait.candidatesEvaluated())));
        rows.add(List.of("тем на тонкой доказательной базе", String.valueOf(portrait.lowEvidenceCount())));
        rows.add(List.of(
                "медианный год первого упоминания",
                portrait.medianFirstMentionYear() == null ? "" : String.valueOf(portrait.medianFirstMentionYear())));
        for (DirectionPortrait.StageCount stage : portrait.byLifecycleStage()) {
            // Русское имя, а не машинное `EMBRYONIC`. Портрет — подписи для человека («тем в
            // отчёте», «оценено кандидатов»), и имя перечисления среди них выглядит недоделкой.
            // Колонка `lifecycle_stage` ниже остаётся машинной намеренно: по ней таблицу
            // фильтруют и сводят, и там стабильное значение важнее читаемости.
            rows.add(List.of("стадия " + stageName(stage.stage()), String.valueOf(stage.count())));
        }
        rows.add(List.of("окно анализа", portrait.windowFrom() + " — " + portrait.windowTo()));
        // Separated the way the evidence column already separates urls: a comma is a plausible part
        // of a source's name, and a reader must not have to guess whether "Bloomberg, L.P." is one
        // source or two — least of all in the row that carries the caveat.
        // «Нет» вместо пустоты — то же правило, что в записке, и по той же причине: пустая ячейка
        // читается как «не заполнено», а «недоступных источников нет» — утверждение об анализе.
        // Правило было записано у записки и не применено здесь; артефакты говорили об одном и том
        // же разными способами.
        rows.add(List.of("источники", joinCellOrNone(portrait.sourcesUsed())));
        rows.add(List.of("недоступные источники", joinCellOrNone(portrait.unavailableSources())));
        rows.add(List.of("корпус собран не полностью", String.valueOf(portrait.partial())));
        rows.add(List.of("направление распознано", String.valueOf(portrait.directionRecognized())));
        // В CSV строка пишется всегда, включая ноль: таблицу читает скрипт, а у скрипта отсутствие
        // строки и ноль — разные вещи, и первое он трактует как «поле не поддерживается».
        rows.add(List.of("скрыто аналитиком", String.valueOf(portrait.suppressedByAnalyst())));
        rows.add(List.of("корпус обрезан пределом", String.valueOf(portrait.corpusTruncated())));
        // Пустая ячейка, а не ноль: «0 из 15 устойчивы» и «устойчивость не измерялась» — разные
        // утверждения, и первое из них скрипт принял бы за измерение.
        rows.add(List.of("тем держатся при любых весах", summary.known() ? String.valueOf(summary.steady()) : ""));
        rows.add(List.of("тем с измеренной устойчивостью", String.valueOf(summary.measured())));

        for (List<String> row : rows) {
            sb.append(escape(row.get(0))).append(';').append(escape(row.get(1))).append('\n');
        }
    }

    private String row(RankedTrend trend) {
        var caseExample = trend.caseExampleOptional();
        var stability = trend.assessment().rankStabilityOptional();
        var cells = List.of(
                String.valueOf(trend.rank()),
                // Пусто, а не ноль и не сам ранг: отчёт без измерения не должен выглядеть идеально
                // устойчивым. Ноль здесь означал бы нулевое место, которого не бывает.
                stability.map(range -> String.valueOf(range.best())).orElse(""),
                stability.map(range -> String.valueOf(range.worst())).orElse(""),
                // В таблице — у каждой темы, а не только у слабой: таблицу фильтруют и сортируют,
                // и колонка с пропусками там означала бы «не измеряли», а не «доля высокая».
                trend.directionShareOptional().map(this::formatNumber).orElse(""),
                trend.title(),
                trend.definition(),
                formatNumber(trend.assessment().score()),
                formatNumber(trend.assessment().confidence()),
                String.valueOf(trend.assessment().lowEvidence()),
                trend.lifecycleStage().name(),
                String.valueOf(trend.firstMentionYear()),
                String.valueOf(trend.totalDocuments()),
                trend.motivation().problem(),
                trend.motivation().benefit(),
                caseExample.map(c -> c.organization()).orElse(""),
                caseExample.map(c -> c.country() == null ? "" : c.country()).orElse(""),
                caseExample.map(c -> c.basis() == null ? "" : c.basis().name()).orElse(""),
                indicator(trend, "novelty"),
                indicator(trend, "growth"),
                indicator(trend, "diffusion"),
                indicator(trend, "weakness"),
                indicator(trend, "coherence"),
                indicator(trend, "impact"),
                trend.evidence().stream()
                        .map(e -> e.url())
                        .reduce((a, b) -> a + " | " + b)
                        .orElse(""));
        return cells.stream().map(this::escape).reduce((a, b) -> a + ";" + b).orElse("");
    }

    /**
     * Отчёт как записка для комитета (BR-A73…BR-A78).
     *
     * <p>Не третий формат ради полноты. CSV читает Excel, JSON читает скрипт, а решение принимает
     * человек, который открывает документ и переносит его в записку руками. Именно на этом переносе
     * продукт терял то, чем ценен: в записку уезжают названия и баллы, а «три из пятнадцати опираются
     * на два документа каждая» остаётся на экране, и дальше решение принимают по цифрам, которым
     * продукт сам не разрешает верить безоговорочно.
     *
     * <p>Оговорки стоят сразу после заголовка, до тем (P2). Сноска в конце отбрасывается при
     * копировании первой половины документа; оговорка, которую нельзя не увидеть, — единственная,
     * которая доезжает.
     *
     * <p>Ни одного предложения, которого нет в отчёте (P3). Связный пересказ читался бы как вывод и
     * не прослеживался бы до источника, а на прослеживаемости продукт и держится.
     *
     * <p>Чистая функция: ни времени, ни текущего пользователя, ни обратной связи. Первое сделало бы
     * две выгрузки одного отчёта разными файлами (P4), второе и третье увезли бы в чужие руки то, что
     * человек писал для себя (P5).
     */
    public String toMarkdown(TrendReport report) {
        var sb = new StringBuilder();
        sb.append("# ").append(inline(report.query().raw())).append('\n');
        sb.append('\n');
        sb.append("Отчёт от ").append(report.generatedAt()).append(", версия ").append(report.version());
        sb.append(", методология ").append(report.methodology().version());
        // Линейку задают три величины, а не одна. Версия была здесь с самого начала; свёртка и
        // профиль весов — нет, хотя балл без них не читается: `MIN_BOUND` ограничен слабейшим
        // индикатором, взвешенное среднее — нет, а профиль решает, какой индикатор весит сколько.
        //
        // Профиль одно время выбирался на форме поиска, и две записки по одному направлению могут
        // стоять на разных весах при одинаковой строке «методология em-1.0.0»; умолчание тоже может
        // смениться. Комитет, сравнивающий два документа, обязан это видеть.
        sb.append(", свёртка ").append(report.methodology().aggregator());
        sb.append(", профиль весов ").append(shortProfile(report.methodology().profileId()));
        sb.append('\n');

        appendCaveats(
                sb,
                DirectionPortrait.of(report),
                report.truncated(),
                StabilitySummary.of(report.trends()),
                report.coverage().documentsAnalyzed());
        appendExclusions(sb, report.coverage().exclusions());

        sb.append('\n').append("## Темы").append('\n');
        for (RankedTrend trend : report.trends()) {
            appendTrend(sb, trend);
        }
        return sb.toString();
    }

    /**
     * Оговорки — блоком, а не строкой.
     *
     * <p>Пишутся всегда, в том числе когда сказать нечего: «недоступных источников нет» — это
     * утверждение об анализе, а его отсутствие читалось бы как «не проверяли».
     */
    private void appendCaveats(
            StringBuilder sb,
            DirectionPortrait portrait,
            boolean truncated,
            StabilitySummary summary,
            int documentsAnalyzed) {
        sb.append('\n').append("## Оговорки").append('\n').append('\n');
        // Основание всего остального, и до сих пор его в записке не было: экран это число
        // показывал, а комитет — тот, кто спрашивает «на скольких документах это основано», —
        // читает записку. Без него «оценено кандидатов: 340» не даёт понять, велика ли выборка.
        sb.append("- Проанализировано документов: ").append(documentsAnalyzed).append('\n');
        sb.append("- Тем в отчёте: ")
                .append(portrait.trendsInReport())
                .append(", оценено кандидатов: ")
                .append(portrait.candidatesEvaluated())
                .append('\n');
        sb.append("- На тонкой доказательной базе: ")
                .append(portrait.lowEvidenceCount())
                .append(" из ")
                .append(portrait.trendsInReport())
                .append('\n');
        sb.append("- Окно анализа: ")
                .append(portrait.windowFrom())
                .append(" — ")
                .append(portrait.windowTo())
                .append('\n');
        sb.append("- Источники: ").append(joinOrNone(portrait.sourcesUsed())).append('\n');
        sb.append("- Недоступные источники: ")
                .append(joinOrNone(portrait.unavailableSources()))
                .append('\n');
        sb.append("- Корпус собран не полностью: ")
                .append(portrait.partial() ? "да" : "нет")
                .append('\n');
        // Обе оговорки печатаются в любом случае — по тому же правилу, что и соседние: утверждение
        // об анализе, которого нет, читается как «не проверяли». Прежняя формулировка правилу
        // следовала, но означала обратное тому, что говорила: «Список усечён: да» значило не «часть
        // списка отрезана», а «методология нашла меньше тем, чем запрошено». Читатель понимал её
        // наоборот, а поле называлось так же, как совсем другой флаг движка — «корпус обрезан
        // пределом». Одно имя на два факта; до комитета доезжал не тот.
        sb.append(
                        truncated
                                ? "- Тем найдено меньше запрошенных: список не обрезался, методология столько и нашла"
                                : "- Тем найдено столько, сколько запрошено")
                .append('\n');
        // Оговорка о полноте самого корпуса, а не о числе тем: «корпус обрезан» означает, что часть
        // литературы не рассматривалась вовсе — предел профиля достигается на широком направлении.
        // Это ограничение полноты, а не результат измерения, и комитет читает отчёт как обзор
        // области.
        sb.append(
                        portrait.corpusTruncated()
                                ? "- Корпус ограничен пределом профиля: часть литературы направления не рассматривалась"
                                : "- Корпус рассмотрен целиком, предел профиля не достигнут")
                .append('\n');
        // Оговорка о человеческом решении в машинном отчёте. Тема, помеченная аналитиком как «не
        // технология», убирается до отбора в ТОП-N, и её место занимает следующий кандидат: список
        // выглядит полным, а состав его — следствие данных и чужого суждения разом. Комитету это
        // знать обязательно, иначе записка заявляет большую объективность, чем имеет.
        //
        // Пишется только когда скрытое есть: строка «скрыто: 0» в каждой записке — шум, который
        // приучает не читать оговорки, а они здесь единственная защита от чрезмерного доверия.
        if (portrait.suppressedByAnalyst() > 0) {
            sb.append("- Скрыто аналитиком как «не технология»: ")
                    .append(portrait.suppressedByAnalyst())
                    .append(" — темы убраны до отбора в ТОП, освободившиеся места заняли следующие кандидаты")
                    .append('\n');
        }
        // Правило, без которого записка выглядит недоделанной. Формулировки проблемы и пользы —
        // дословные цитаты, и часто на языке источника. Читатель, не знающий правила, видит
        // недопереведённый текст; знающий — видит первоисточник, который может открыть и
        // проверить. Разница только в одной строке, и она стоит того, чтобы стоять здесь, а не в
        // документации, которую к записке не приложат.
        // Ответ на вопрос, который комитет задаёт первым и которого записка до сих пор не слышала:
        // «насколько этот список — вывод, а не следствие ваших настроек?». Веса шести индикаторов
        // выбраны экспертно, и без этой строки любой ответ сводится к «доверьтесь нам».
        //
        // Молчание, когда не измеряли: доля «0 из 15» выглядела бы измерением и была бы неправдой.
        if (summary.known()) {
            sb.append("- Устойчивость к весам: ")
                    .append(summary.steady())
                    .append(" из ")
                    .append(summary.measured())
                    .append(" тем остаются в отчёте при любом из разумных наборов весов индикаторов");
            if (summary.weightDependent() > 0) {
                sb.append("; присутствие остальных ")
                        .append(summary.weightDependent())
                        .append(" зависит от выбранного взвешивания и требует ручной проверки");
            }
            sb.append('\n');
        }
        sb.append("- Проблема и польза у каждой темы — дословные цитаты из источников, ")
                .append("на языке оригинала: перевод перестал бы быть цитатой, ")
                .append("а пересказ нельзя было бы проверить по первоисточнику.\n");
        if (!portrait.directionRecognized()) {
            // Не строка в перечне, а отдельный абзац, и до содержания отчёта. Записка ходит по
            // почте отдельно от интерфейса: если оговорку можно прочитать после пятнадцати
            // обоснованных трендов, её прочитают после решения.
            sb.append('\n')
                    .append("> **Направление не распознано.** Движок не смог соотнести запрошенное ")
                    .append("направление со словарём предметных кодов, которым размечен корпус. ")
                    .append("Отбор тем ниже не зависит от заданного вопроса и не может служить ")
                    .append("основанием для решения. Переформулируйте направление или пополните ")
                    .append("перекрёстный словарь.")
                    .append('\n');
            if (!portrait.directionSuggestions().isEmpty()) {
                sb.append(">\n> Возможно, вы имели в виду: ")
                        .append(String.join(", ", portrait.directionSuggestions()))
                        .append(".\n");
            }
        }
    }

    /**
     * Что не попало в отчёт и почему.
     *
     * <p>Блок требует ТЗ: «должна быть продемонстрирована логика исключения зрелых трендов,
     * массово внедренных технологий, отраслевых стандартов, маркетингового хайпа и
     * информационного шума». Комитет читает записку, а не экран, и вопрос «что вы отбросили»
     * задают там же, где спрашивают «на чём это основано».
     *
     * <p>Примеры идут вместе со счётчиком: число без имён проверить нельзя, и молчание о них —
     * ровно тот случай, когда отчёт производит уверенность вместо сведений.
     *
     * <p>Пустой перечень означает «движок причин не присылал» — старая версия. Строка «отсеяно
     * ноль» была бы неправдой: на непустом корпусе такого не бывает.
     */
    private void appendExclusions(StringBuilder sb, java.util.List<Exclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) {
            return;
        }
        sb.append('\n').append("## Что не попало в отчёт и почему").append('\n').append('\n');
        for (Exclusion exclusion : exclusions) {
            sb.append("- ")
                    .append(inline(exclusion.reason()))
                    .append(": ")
                    .append(exclusion.count())
                    .append(" кандидат(ов)");
            if (!exclusion.examples().isEmpty()) {
                sb.append(" — например: ").append(inline(String.join(", ", exclusion.examples())));
            }
            sb.append('\n');
        }
    }

    private void appendTrend(StringBuilder sb, RankedTrend trend) {
        // Русское название — в заголовке, оригинальное — сразу под ним. ТЗ требует обоих: вся
        // аналитическая выдача на русском языке, но оригинальное название сохраняется, иначе
        // читатель не найдёт тему в самом источнике.
        var localization = trend.localization();
        var titleRu = localization.title();
        var translated = titleRu != null
                && !titleRu.isBlank()
                && !titleRu.trim().equals(trend.title().trim());
        sb.append('\n')
                .append("### ")
                .append(trend.rank())
                .append(". ")
                .append(inline(translated ? titleRu : trend.title()))
                .append('\n');
        if (translated) {
            sb.append('\n').append("Оригинальное название: ").append(inline(trend.title()));
            if (localization.titleModel() != null && !localization.titleModel().isBlank()) {
                sb.append(" (машинный перевод, модель ")
                        .append(inline(localization.titleModel()))
                        .append(")");
            }
            sb.append('\n');
        }
        // Тренд одним предложением — сразу под названием: записку читают те, кто отчёт не открывал,
        // и словосочетание в заголовке им не говорит, что происходит. Модель названа рядом — это
        // пересказ, а не цитата.
        var statement = localization.statement();
        if (statement != null && !statement.isBlank()) {
            sb.append('\n').append("Тренд: ").append(inline(statement));
            if (localization.statementModel() != null && !localization.statementModel().isBlank()) {
                sb.append(" (сформулировано моделью ")
                        .append(inline(localization.statementModel()))
                        .append(" по источникам темы)");
            }
            sb.append('\n');
        }
        var definitionRu = localization.definition();
        sb.append('\n')
                .append(blockText(definitionRu != null && !definitionRu.isBlank() ? definitionRu : trend.definition()))
                .append('\n');
        // Кавычки, а не двоеточие. Формулировка — дословная цитата из источника и часто на языке
        // оригинала; поданная как обычный пункт, она читается как утверждение платформы на ломаном
        // языке, а не как то, что действительно написано в первоисточнике. Правило целиком
        // объяснено в оговорках, здесь — только знак того, что это чужие слова.
        sb.append('\n');
        appendQuoted(sb, trend, "problem", "Проблема", trend.motivation().problem());
        appendQuoted(sb, trend, "benefit", "Польза", trend.motivation().benefit());
        sb.append("- Стадия: ")
                .append(stageName(trend.lifecycleStage()))
                // «Достоверное» — по BRULE-2: первый год, когда тему подтверждают два документа
                // из двух организаций. Документы бывают и раньше, у 21 темы из 90 на два года и
                // больше, и записка не должна называть этот год первым упоминанием вообще.
                .append(", первое достоверное упоминание: ")
                .append(trend.firstMentionYear())
                .append(", документов: ")
                .append(trend.totalDocuments())
                .append('\n');
        // Балл и уверенность рядом намеренно: балл без уверенности — это то самое число без оговорки,
        // ради которого весь документ и написан иначе, чем таблица.
        sb.append("- Балл: ")
                .append(readable(trend.assessment().score(), 1))
                .append(", уверенность: ")
                .append(readable(trend.assessment().confidence(), 2));
        if (trend.assessment().lowEvidence()) {
            sb.append(" — тонкая доказательная база");
        }
        sb.append('\n');
        // Место темы отдельной строкой и только вместе с диапазоном: «третья в направлении» без
        // него неотличимо от следствия того, как мы взвесили. Строка молчит, когда измерения не
        // было, — записка не должна утверждать надёжность, которой никто не проверял.
        trend.assessment().rankStabilityOptional().ifPresent(range -> {
            sb.append("- Место: ").append(trend.rank());
            if (range.immovable()) {
                sb.append(" — не меняется ни при одном из рассмотренных наборов весов");
            } else {
                sb.append(", при других весах — с ")
                        .append(range.best())
                        .append("-го по ")
                        .append(range.worst())
                        .append("-е");
            }
            sb.append('\n');
        });
        // Только у слабой доли. Строка под каждой темой — шум, а шум приучает не читать оговорки;
        // в записке это единственная защита от чрезмерного доверия. Порог показа не является
        // порогом отбора: он решает, о чём говорить, и ошибка в нём стоит лишней строки, а не
        // потерянной темы.
        trend.directionShareOptional()
                .filter(share -> share < WEAK_DIRECTION_SHARE)
                .ifPresent(share -> sb.append("- К направлению отнесены ")
                        .append(Math.round(share * trend.totalDocuments()))
                        .append(" из ")
                        .append(trend.totalDocuments())
                        .append(" документов темы — остальные относятся к другим областям")
                        .append('\n'));
        trend.caseExampleOptional().ifPresent(example -> sb.append("- ")
                .append(caseBasisLabel(example.basis()))
                .append(": ")
                .append(inline(example.organization()))
                .append(example.country() == null ? "" : " (" + inline(example.country()) + ")")
                .append('\n'));

        // Отметка, которую ТЗ требует вместо удаления темы: сведения из медиа и пресс-релизов не
        // должны быть единственным основанием для включения «либо сопровождаться отметкой о
        // пониженной доверенности». Пишется только когда верна — строка «доверенность обычная» в
        // каждой теме приучает не читать оговорки.
        if (trend.lowCredibilityOnly()) {
            sb.append("- Все источники темы низкой доверенности: вывод требует независимого подтверждения")
                    .append('\n');
        }

        // Страница инсайта по ТЗ объясняет статус слабого сигнала и уверенность; записка — та же
        // карточка на бумаге, поэтому несёт объяснение целиком.
        if (!trend.explanation().isEmpty()) {
            sb.append('\n').append("Почему это слабый сигнал:").append('\n');
            for (var item : trend.explanation()) {
                sb.append("- ").append(inline(item.title())).append(": ").append(inline(item.text())).append('\n');
            }
        }

        sb.append('\n').append("Источники:").append('\n');
        for (var evidence : trend.evidence()) {
            sb.append("- ")
                    .append(reference(evidence))
                    .append(sourceTrust(evidence))
                    .append('\n');
        }
    }

    /**
     * Язык оригинала и уровень доверенности возле источника.
     *
     * <p>ТЗ перечисляет их наравне с наименованием, ссылкой, датой и типом: «для каждого источника
     * система должна отображать наименование, ссылку, дату публикации, тип источника, язык
     * оригинала и уровень доверенности». Записка — такой же способ прочитать отчёт, как экран.
     */
    private String sourceTrust(dev.horizon.trends.domain.report.Evidence evidence) {
        var parts = new java.util.ArrayList<String>(3);
        if (evidence.language() != null && !evidence.language().isBlank()) {
            parts.add("язык " + inline(evidence.language()));
        }
        parts.add("доверенность " + credibilityLabel(evidence.credibility()));
        if (!evidence.independent()) {
            parts.add("не независимое свидетельство");
        }
        return " — " + String.join(", ", parts);
    }

    /** Подпись уровня доверенности по-русски: вся выдача на русском языке (ТЗ). */
    private static String credibilityLabel(dev.horizon.trends.domain.report.Credibility credibility) {
        return switch (credibility) {
            case HIGH -> "высокая";
            case MEDIUM -> "средняя";
            case LOW -> "пониженная";
        };
    }

    /**
     * Ссылка на источник — проверяемая (BR-A75, P7).
     *
     * <p>DOI предпочтительнее ссылки: он переживает переезд издателя. Случая «нет ни того, ни другого»
     * не существует — домен требует непустой url, — но адрес может оказаться не веб-схемы, и тогда
     * пишется источник с его идентификатором: строка без ссылки в записке выглядит как утверждение
     * без основания, а основание есть, просто оно другое.
     */
    private String reference(dev.horizon.trends.domain.report.Evidence evidence) {
        var raw = evidence.title() == null || evidence.title().isBlank() ? evidence.externalId() : evidence.title();
        var title = inline(raw);
        if (evidence.doi() != null && !evidence.doi().isBlank()) {
            return link(title, "https://doi.org/" + evidence.doi().trim());
        }
        var url = evidence.url() == null ? "" : evidence.url().trim();
        if (isWebLink(url)) {
            return link(title, url);
        }
        // Ссылка есть, но не веб-схема — источник назван, ссылка не поставлена. Молчаливое
        // умолчание здесь было бы хуже: строка без основания в записке читается как утверждение
        // без основания, а основание есть, просто оно другое.
        return title + " — " + inline(evidence.sourceId()) + ":" + inline(evidence.externalId());
    }

    /**
     * Только http и https.
     *
     * <p>Адрес приходит из внешнего источника, а документ открывают в редакторе, где ссылка
     * кликабельна. {@code javascript:} и {@code data:} в записке для комитета — это не ссылка на
     * доказательство, а то, чему в ней делать нечего. Всё остальное отдаётся текстом, а не молча
     * выбрасывается.
     */
    private static boolean isWebLink(String url) {
        var lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Скобки и пробелы в адресе рвут разметку ссылки — процентируются, как того требует URL.
     *
     * <p>Пробел особенно коварен: по CommonMark адрес с ним перестаёт быть адресом, и строка
     * рендерится буквально, вместе с видимыми обратными слэшами. Ссылки у источника при этом нет, а
     * сказать об этом некому — требование «у каждой темы проверяемая ссылка» нарушается молча.
     */
    private static String link(String title, String url) {
        var encoded = url.replace("%", "%25")
                .replace("(", "%28")
                .replace(")", "%29")
                // Перевод строки внутри адреса — не косметика, а выход из ссылки. Проверено на
                // враждебном DOI «10.1/x\n\n# Поддельный заголовок»: строки выживали и попадали в
                // тело записки, а по CommonMark ссылка при этом рвалась вовсе. Путь ведёт от
                // метаданных внешнего источника до документа, который читает комитет.
                //
                // Путь через DOI особенно легко упустить: он собирает адрес сам, из «https://doi.org/»
                // и чужой строки, и потому не проходит проверку схемы — та применяется только к
                // готовому адресу.
                .replace("\r", "%0D")
                .replace("\n", "%0A")
                .replace("\t", "%09")
                .replace(" ", "%20");
        return "[" + title + "](" + encoded + ")";
    }

    /**
     * Формулировка — по предложению, каждое со своим источником.
     *
     * <p>Прежде абзац печатался целиком одной цитатой. Он склеен из предложений разных статей: по
     * эталонному корпусу 166 формулировок из 180 собраны из двух и более документов, и в 51 из них
     * второе предложение говорит «the proposed method» или «we show» — о системе другого автора.
     * Слитый абзац читается как один довод одной работы, которого не делал никто.
     *
     * <p>Запасной путь — когда предложения не записаны (отчёт выпущен до появления поля): тогда
     * печатается абзац целиком, как раньше. Хуже, чем по предложениям, но честнее выдуманного
     * разбиения: точка стоит и внутри «e.g.», и в «1.8x».
     */
    private void appendQuoted(StringBuilder sb, RankedTrend trend, String statement, String label, String whole) {
        var parts = trend.motivation().attributions().stream()
                .filter(a -> statement.equals(a.statement()))
                .filter(a -> a.sentence() != null && !a.sentence().isBlank())
                .toList();
        if (parts.isEmpty()) {
            sb.append("- ")
                    .append(label)
                    .append(" — цитата: «")
                    .append(inline(whole))
                    .append("»\n");
            return;
        }
        sb.append("- ").append(label).append(" — цитаты:\n");
        for (var part : parts) {
            sb.append("    - «").append(inline(part.sentence())).append("»");
            var source = sourceOf(trend, part.evidenceIndex());
            if (source != null) {
                sb.append(" — ").append(inline(source));
            }
            sb.append('\n');
        }
    }

    /** Название документа, из которого взято предложение; {@code null}, если ссылка ведёт в пустоту. */
    private String sourceOf(RankedTrend trend, int evidenceIndex) {
        var evidence = trend.evidence();
        if (evidenceIndex < 0 || evidenceIndex >= evidence.size()) {
            return null;
        }
        return evidence.get(evidenceIndex).title();
    }

    /**
     * Профиль весов — восемью знаками идентификатора.
     *
     * <p>Имени здесь нет намеренно: чтобы его получить, выгрузке пришлось бы обращаться к
     * хранилищу профилей, то есть связать формирование документа с персистентностью ради подписи.
     * Восьми знаков достаточно для вопроса, который записка обязана поддержать: сравнимы ли два
     * документа между собой. Полное имя видно на экране отчёта.
     */
    private static String shortProfile(java.util.UUID profileId) {
        return profileId.toString().substring(0, 8);
    }

    /**
     * Подпись к кейс-примеру — по тому правилу §8, которое его подобрало.
     *
     * <p>Прежде здесь стояло «Пример внедрения» для всех трёх правил разом. По эталонному корпусу
     * это означало, что 61 тема из 90 объявлялась внедрённой, имея в основании публикацию, и 21 из
     * них — препринт. Записку читает комитет, который по этой строке называет компанию вслух;
     * «работает над темой» и «внедрила» он различает, если ему дать различить.
     */
    private static String caseBasisLabel(CaseExample.Basis basis) {
        if (basis == null) {
            // Отчёт, выпущенный до появления основания. Утверждается только то, что известно:
            // организация встречается в документах темы.
            return "Организация в документах темы";
        }
        return switch (basis) {
            case PATENT -> "Патент компании (право на применение закреплено)";
            case CORPORATE_PUBLICATION -> "Публикация компании (работает над темой; о внедрении не говорит)";
            case ACADEMIC_GROUP -> "Академическая группа (корпоративного следа у темы нет)";
        };
    }

    /**
     * Недоверенный текст внутри строки документа.
     *
     * <p>Название темы и определение приходят из внешних данных и попадают в разметку. Перевод
     * строки внутри пункта списка разорвал бы список, ведущая решётка превратила бы название в
     * заголовок, а квадратная скобка со скобкой круглой — в ссылку, которой автор отчёта не писал.
     * Экранируется ровно то, что меняет структуру или подделывает ссылку: подчёркивания и дефисы
     * внутри слов оставлены как есть, потому что escape-каша читается хуже, чем риск курсива.
     */
    private static String inline(String text) {
        if (text == null) {
            return "";
        }
        var collapsed = text.replaceAll("\\s+", " ").trim();
        var escaped = collapsed
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
                // Угловая скобка — не украшение: Markdown пропускает сырой HTML, и запрет небезопасных
                // схем в поле ссылки обходился бы тегом <a href="javascript:…"> в соседнем поле.
                // Защита, закрывающая одну дверь из двух, выглядит полной и таковой не является.
                .replace("<", "\\<");
        return escaped;
    }

    /**
     * Недоверенный текст, который начинает строку документа.
     *
     * <p>Отдельно от {@link #inline}: экранировать начало имеет смысл только там, где начало есть.
     * Внутри строки «arxiv:2403.0001» ведущая проверка сработала бы на идентификаторе источника и
     * испортила бы его слэшем — первая версия ровно это и делала.
     */
    private static String blockText(String text) {
        var escaped = inline(text);
        return needsLeadingEscape(escaped) ? "\\" + escaped : escaped;
    }

    /**
     * Начало строки, меняющее её роль в документе.
     *
     * <p>Решётка делает из названия заголовок, а дефис или «1.» — пункт списка, неотличимый от
     * настоящих. Подделать таким образом можно и строку «- Балл: 99,9, уверенность: 1,00», то есть
     * число без происхождения внутри блока темы, — а весь формат написан ради обратного.
     */
    private static boolean needsLeadingEscape(String text) {
        if (text.isEmpty()) {
            return false;
        }
        var first = text.charAt(0);
        if (first == '#' || first == '>' || first == '-' || first == '+' || first == '=') {
            return true;
        }
        // «1.» и «1)» в начале строки — нумерованный список.
        int digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        return digits > 0 && digits < text.length() && (text.charAt(digits) == '.' || text.charAt(digits) == ')');
    }

    /** Балл и уверенность для человека, а не для формулы: четыре знака в записке — шум. */
    private static String readable(double value, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", value)
                .replace('.', ',');
    }

    /**
     * Стадия по-русски: записку читает комитет, а не разработчик.
     *
     * <p>Латинское имя перечисления в русском документе — это термин, который читателю предлагают
     * перевести самому, и половина переведёт неверно.
     */
    /**
     * Название стадии — теми же словами, что на экране.
     *
     * <p>Здесь были свои: «зарождение», «появление», «ускорение», «зрелость». Беда не в том, что они
     * другие, а в том, что они **перекрещиваются** с экранными: записка называла `EMBRYONIC`
     * «зарождением», а экран называет «зарождающейся» соседнюю стадию `EMERGING`. Читатель, видевший
     * на экране «Зарождающаяся» и встретивший в записке «зарождение», уверенно принимает одно за
     * другое и ошибается на шаг.
     *
     * <p>Словарь экрана (`frontend/src/lib/i18n/ru.ts`, группа `lifecycle`) — источник правды: он
     * переведён на оба языка и снабжён подсказками с правилами стадий. Совпадение проверяется
     * исполняемо, чтением того самого файла.
     */
    /** Имя стадии по её машинному значению — для портрета, где стадии приходят строками. */
    private static String stageName(String stage) {
        return stageName(dev.horizon.trends.domain.report.LifecycleStage.valueOf(stage));
    }

    private static String stageName(dev.horizon.trends.domain.report.LifecycleStage stage) {
        return switch (stage) {
            case EMBRYONIC -> "Зачаточная";
            case EMERGING -> "Зарождающаяся";
            case ACCELERATING -> "Ускоряющаяся";
            case MATURING -> "Зрелая";
        };
    }

    /**
     * Ячейка таблицы: «нет» вместо пустоты, разделитель — вертикальная черта.
     *
     * <p>Правило «нет» то же, что у записки, и по той же причине: пустая ячейка читается как «не
     * заполнено», а «недоступных источников нет» — утверждение об анализе. А вот разделитель у
     * таблицы свой и остаётся своим: запятая — правдоподобная часть имени источника, и «Bloomberg,
     * L.P.» в ячейке читался бы как два источника. Первая редакция этой правки переиспользовала
     * помощника записки целиком и вернула бы ту самую двусмысленность вместе с markdown-экранированием,
     * в таблице бессмысленным; поймал существующий тест на экранирование.
     */
    private static String joinCellOrNone(List<String> values) {
        return values.isEmpty() ? "нет" : String.join(" | ", values);
    }

    /** «Нет» вместо пустоты: пустая строка читается как «не заполнено», а это разные утверждения. */
    private String joinOrNone(List<String> values) {
        return values.isEmpty()
                ? "нет"
                : values.stream().map(ExportReportUseCase::inline).collect(java.util.stream.Collectors.joining(", "));
    }

    private String indicator(RankedTrend trend, String name) {
        return trend.assessment().indicators().stream()
                .filter(i -> i.name().equals(name))
                .findFirst()
                .map(i -> formatNumber(i.value()))
                .orElse("");
    }

    private String formatNumber(double value) {
        // Comma decimal separator for ru-RU locale spreadsheets; semicolon is the field separator.
        return String.format(java.util.Locale.ROOT, "%.4f", value).replace('.', ',');
    }

    /**
     * Символы, с которых Excel и LibreOffice начинают читать ячейку как формулу.
     *
     * <p>Список по OWASP (CWE-1236). Минус включён намеренно, хотя с него начинаются отрицательные
     * числа: сегодня в этой таблице их нет, а если появятся, освобождать их надо осознанно и
     * отдельной строкой — не тем, что кто-то забудет вернуть защиту.
     *
     * <p>Возврата каретки здесь нет, хотя OWASP его называет: он заменяется пробелом строкой выше,
     * и до этой проверки не доходит. Мёртвая ветка в защите хуже её отсутствия — она создаёт
     * впечатление, что случай разобран, и следующий читатель не станет его проверять.
     */
    private static final String FORMULA_STARTERS = "=+-@\t";

    /**
     * Экранирование ячейки CSV — включая защиту от исполнения её формулой.
     *
     * <p>Заголовки тем и названия источников приходят из внешних документов: препринтов, патентов и
     * RSS-лент, публиковать которые может кто угодно. Статья с названием
     * {@code =HYPERLINK("http://…";"Отчёт")} превращается в кликабельную ссылку в таблице аналитика,
     * а исторические сценарии с DDE запускали и внешние программы. Файл при этом — тот самый, что
     * несут комитету, и открывают его именно в Excel: ради этого выше стоит BOM.
     *
     * <p>Опасное начало обезвреживается апострофом: Excel читает его как «дальше текст» и не
     * показывает. Значение при этом меняется, и это осознанный размен — тот же, что уже сделан в
     * пользу BOM: CSV здесь формат для человека, а машине предназначена выгрузка JSON, которая
     * остаётся дословной.
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", " ").replace("\n", " ");
        if (!cleaned.isEmpty() && FORMULA_STARTERS.indexOf(cleaned.charAt(0)) >= 0) {
            cleaned = "'" + cleaned;
        }
        if (cleaned.contains(";") || cleaned.contains("\"")) {
            return '"' + cleaned.replace("\"", "\"\"") + '"';
        }
        return cleaned;
    }
}
