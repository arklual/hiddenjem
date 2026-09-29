package dev.horizon.ingestion.connector.regulators;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор страницы «Regulatory Sandbox accepted firms» FCA.
 *
 * <p>Страница — одна таблица на все годы: фирма, описание испытываемого продукта и когда принята
 * («Cohorts 1 to 7», «2021 to 2025», «2026», «Stablecoin cohort 2026»). Каждая строка — отдельная
 * запись: фирма и её продукт, прошедшие отбор регулятора, — ранний рыночный сигнал, и организация
 * у него — сама фирма, а не FCA.
 *
 * <p><b>Дата — «не позже чем».</b> Точной даты приёма у строки нет, только период. Берётся конец
 * периода, но не позже последнего обновления страницы: к этому дню запись заведомо была публичной.
 * Для «Cohorts 1 to 7» конец периода — 31.12.2020: сама страница пишет «Before 2021, we used a cohort
 * model». Так дата никогда не раньше настоящей — бэктест по срезам не увидит фирму до того, как
 * о ней объявили; обратная ошибка (фирма моложе своей даты на год-два) для раннего сигнала
 * безопаснее, чем заглядывание в будущее.
 */
final class FcaSandbox {

    private static final Pattern TABLE = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern MODIFIED =
            Pattern.compile("<meta property=\"article:modified_time\" content=\"([^\"]+)\"");
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d\\d)\\b");
    /** Последний год когортной модели — со слов самой страницы. */
    static final LocalDate COHORTS_END = LocalDate.of(2020, 12, 31);

    private FcaSandbox() {}

    /** Строка таблицы: фирма, описание, период приёма и выведенная из него дата. */
    record Firm(String name, String description, String accepted, LocalDate date) {}

    /** Страница целиком: дата последнего обновления и фирмы. */
    record Page(LocalDate modifiedOn, List<Firm> firms) {}

    static Page parse(String html) {
        Matcher modified = MODIFIED.matcher(html);
        LocalDate modifiedOn = modified.find() ? parseDate(modified.group(1)) : null;
        List<Firm> firms = new ArrayList<>();
        Matcher table = TABLE.matcher(html);
        while (table.find()) {
            Matcher row = ROW.matcher(table.group(1));
            while (row.find()) {
                List<String> cells = new ArrayList<>();
                Matcher cell = CELL.matcher(row.group(1));
                while (cell.find()) {
                    cells.add(HtmlText.of(cell.group(1)));
                }
                if (cells.size() < 3 || cells.get(0) == null || cells.get(1) == null) {
                    // Заголовок таблицы — в <th>, у него <td> нет.
                    continue;
                }
                String accepted = cells.get(2) == null ? "" : cells.get(2);
                firms.add(new Firm(cells.get(0), cells.get(1), accepted, dateOf(accepted, modifiedOn)));
            }
        }
        return new Page(modifiedOn, firms);
    }

    /** Конец периода приёма, но не позже обновления страницы; см. описание класса. */
    static LocalDate dateOf(String accepted, LocalDate modifiedOn) {
        LocalDate end = null;
        Matcher year = YEAR.matcher(accepted);
        while (year.find()) {
            end = LocalDate.of(Integer.parseInt(year.group(1)), 12, 31);
        }
        if (end == null && accepted.toLowerCase(Locale.ROOT).contains("cohort")) {
            end = COHORTS_END;
        }
        if (end == null) {
            return modifiedOn;
        }
        return modifiedOn != null && modifiedOn.isBefore(end) ? modifiedOn : end;
    }

    private static LocalDate parseDate(String value) {
        try {
            return OffsetDateTime.parse(value.trim()).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
