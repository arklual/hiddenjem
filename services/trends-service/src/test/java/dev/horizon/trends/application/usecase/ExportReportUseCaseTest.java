package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.support.Fixtures;

/**
 * CSV export (FR-03.8) — and specifically what it must not lose.
 *
 * <p>The CSV is the artefact that reaches a committee: it gets opened in Excel and pasted into a
 * slide. An export carrying only the fifteen rows delivers every number intact with the sentence
 * "three of these rest on two documents each" removed, which is the failure BR-A17 exists to
 * prevent.
 */
class ExportReportUseCaseTest {

    // Portrait on: the block below the BOM is the subject of most of these cases.
    private final ExportReportUseCase export =
            new ExportReportUseCase(new FeatureGate(new FeatureFlags(new MockEnvironment())));
    private final dev.horizon.trends.domain.research.ResearchRequest request = Fixtures.assemblingRequest();

    private String csvOf(List<String> keys) {
        return export.toCsv(Fixtures.reportWithTrends(request, "em-1.0.0", keys, 50.0));
    }

    @Test
    @DisplayName("портрет называет стадии теми же словами, что записка и экран")
    void thePortraitNamesStagesInRussian() {
        // Портрет — подписи для человека, и машинное `EMBRYONIC` среди них выглядит недоделкой.
        // Третий словарь на один перечислимый тип: экран говорил «Зачаточная», записка —
        // «зарождение», таблица — «EMBRYONIC».
        String csv = csvOf(List.of("a"));

        assertThat(csv).contains("стадия Зарождающаяся;");
        assertThat(csv).doesNotContain("стадия EMERGING;");
    }

    @Test
    @DisplayName("колонка стадии остаётся машинной: по ней фильтруют и сводят")
    void theStageColumnStaysMachineReadable() {
        // Обратная сторона: перевести и колонку значило бы сломать фильтры и сводные таблицы ради
        // единообразия там, где оно не нужно.
        assertThat(csvOf(List.of("a"))).contains(";EMERGING;");
    }

    @Test
    @DisplayName("«нет» вместо пустой ячейки — то же правило, что в записке")
    void anEmptyListReadsAsNoneRatherThanBlank() {
        // Пустая ячейка читается как «не заполнено», а «недоступных источников нет» — утверждение
        // об анализе. Правило было записано у записки и не применялось к таблице.
        String csv = csvOf(List.of("a"));

        assertThat(csv).contains("недоступные источники;нет");
        assertThat(csv).doesNotContain("недоступные источники;\n");
    }

    @Test
    void startsWithTheByteOrderMarkSoExcelReadsCyrillic() {
        // Without it the whole export is unusable for its audience, whatever else it contains.
        assertThat(csvOf(List.of("a"))).startsWith("﻿");
    }

    @Test
    void carriesTheCaveatsAboveTheTableRatherThanBelowIt() {
        // Position is the point: a qualification placed after fifteen rows has been hidden, and the
        // reader who scrolls to it is the one who did not need it.
        String csv = csvOf(List.of("a", "b", "c"));

        int caveat = csv.indexOf("тем на тонкой доказательной базе");
        int table = csv.indexOf("emergence_score");
        assertThat(caveat).isNotNegative();
        assertThat(caveat).isLessThan(table);
    }

    @Test
    void namesTheSourcesThatWereNotAvailable() {
        assertThat(csvOf(List.of("a"))).contains("недоступные источники");
    }

    @Test
    void statesTheAnalysisWindowTheNumbersRefersTo() {
        assertThat(csvOf(List.of("a"))).contains("окно анализа");
    }

    @Test
    void keepsTheTrendTableIntactBelowThePortrait() {
        // The block is added, not substituted: whoever reads the table must still find it whole.
        String csv = csvOf(List.of("a", "b", "c"));
        var lines = csv.lines().toList();

        int header = lines.indexOf(lines.stream()
                .filter(line -> line.startsWith("rank;"))
                .findFirst()
                .orElseThrow());
        assertThat(lines.subList(header + 1, lines.size())).hasSize(3);
    }

    @Test
    void separatesThePortraitFromTheTableWithABlankLine() {
        // Excel reads two blocks; a parser that wants only the table can skip to the blank line.
        String csv = csvOf(List.of("a"));

        assertThat(csv.lines().toList()).contains("");
    }

    @Test
    void writesAZeroCountRatherThanOmittingTheRow() {
        // "недоступных источников нет" is a statement about the analysis; an absent row reads as
        // "not checked".
        String csv = csvOf(List.of("a"));

        assertThat(csv).contains("корпус собран не полностью");
        assertThat(csv).containsPattern("стадия Зрелая;\\d");
    }

    @Test
    void escapesASourceNameContainingTheSeparator() {
        // Until now every test shared a fixture whose source names contain no `;`, no quote and no
        // newline — so an implementation that forgot to escape the portrait would have passed all of
        // them. The row that carries the caveat is the last one that should be allowed to shift the
        // columns of the whole file.
        var coverage = Fixtures.coverageWithSources(
                List.of("Bloomberg; L.P.", "Reuters"), List.of("Внутренний \"архив\"", "patents\nview"));

        String csv = export.toCsv(Fixtures.reportWithCoverage(request, coverage));
        String sources = lineStartingWith(csv, "источники;");
        String unavailable = lineStartingWith(csv, "недоступные источники;");

        // Quoted as one field, inner quotes doubled, newline flattened — the table below stays put.
        assertThat(sources).isEqualTo("источники;\"Bloomberg; L.P. | Reuters\"");
        assertThat(unavailable).isEqualTo("недоступные источники;\"Внутренний \"\"архив\"\" | patents view\"");
    }

    @Test
    void namesEveryUnavailableSourceRatherThanTheirNumber() {
        // The point of the row is which source is missing: "2" would tell the committee nothing.
        var coverage = Fixtures.coverageWithSources(List.of("arxiv"), List.of("uspto", "crossref"));

        String csv = export.toCsv(Fixtures.reportWithCoverage(request, coverage));

        assertThat(lineStartingWith(csv, "недоступные источники;"))
                .contains("uspto")
                .contains("crossref");
    }

    private static String lineStartingWith(String csv, String prefix) {
        return csv.lines()
                .map(line -> line.startsWith("\uFEFF") ? line.substring(1) : line)
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("строка не найдена: " + prefix));
    }

    @Test
    void omitsThePortraitBlockWhenTheFeatureIsOff() {
        // Not an empty block and not a header with no rows: the file must look like the one this
        // build promises, or a reader would wonder what happened to the caveats.
        var off = new MockEnvironment();
        off.setProperty(FeatureFlag.DIRECTION_PORTRAIT.property(), "false");
        var without = new ExportReportUseCase(new FeatureGate(new FeatureFlags(off)));

        String csv = without.toCsv(Fixtures.reportWithTrends(request, "em-1.0.0", List.of("a"), 50.0));

        assertThat(csv).doesNotContain("тем на тонкой доказательной базе");
        assertThat(csv.lines().toList().getFirst()).contains("rank;");
    }

    @Test
    void anEmptyReportStillExportsItsPortrait() {
        String csv = csvOf(List.of());

        assertThat(csv).contains("тем в отчёте;0");
        assertThat(csv).contains("emergence_score");
    }
}
