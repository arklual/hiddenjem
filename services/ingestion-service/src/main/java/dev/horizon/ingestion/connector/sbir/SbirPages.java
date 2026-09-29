package dev.horizon.ingestion.connector.sbir;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

import dev.horizon.ingestion.connector.sbir.model.SbirAward;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Разбор двух страниц SBIR.gov: выдачи поиска {@code /awards?keywords=…} и страницы награды.
 *
 * <p><b>Почему HTML, а не API.</b> Документированный JSON-интерфейс
 * {@code api.www.sbir.gov/public/api/awards} отвечает {@code 403} и с рабочей машины, и со стенда
 * (проверено 2026-09-28). HTML-поиск открыт, {@code robots.txt} закрывает только {@code /search/},
 * служебные пути Drupal и вход — {@code /awards} не закрыт.
 *
 * <p><b>Выдача и отказ различаются по разметке, а не по числу ссылок.</b> Пустая выдача печатает
 * «No results found.», непустая — «Showing 1-10 of 74 results». Страница без того и другого — не
 * выдача вовсе (заглушка защиты, сменённая вёрстка), и принять её за ноль значило бы тихо потерять
 * источник: это отказ.
 */
final class SbirPages {

    private static final Pattern NO_RESULTS = Pattern.compile("No results found\\.");
    private static final Pattern SHOWING = Pattern.compile("Showing\\s+(\\d+)-(\\d+)\\s+of\\s+([\\d,]+)\\s+results");
    private static final Pattern RESULT_LINK = Pattern.compile("<h4[^>]*>\\s*<a href=\"/awards/(\\d+)\">");
    private static final Pattern YEAR_TAG =
            Pattern.compile("text-no-uppercase margin-top-1\">\\s*((?:19|20)\\d\\d)\\s*</p>");

    private static final Pattern TITLE =
            Pattern.compile("Back to Award Search</a></p>\\s*<h2>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern COMPANY =
            Pattern.compile("<h3[^>]*>\\s*Awardee\\s*</h3>\\s*(?:<a[^>]*>)?\\s*<h4[^>]*>(.*?)</h4>", Pattern.DOTALL);
    private static final Pattern AWARD_YEAR = Pattern.compile("<strong>Award Year:</strong>\\s*((?:19|20)\\d\\d)");
    private static final Pattern START_DATE =
            Pattern.compile("<strong class=\"text-secondary\">([^<]*)</strong><br>\\s*Award Start Date");
    private static final Pattern AGENCY = Pattern.compile(
            "<h3>\\s*Awarding Agency\\s*</h3>\\s*<p>(.*?)</p>(?:\\s*<p>Branch:\\s*(.*?)</p>)?", Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("Tagged as:</p>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern TAG =
            Pattern.compile("<p class=\"[^\"]*text-no-uppercase[^\"]*\">(.*?)</p>", Pattern.DOTALL);
    private static final Pattern ABSTRACT = Pattern.compile("<p class=\"measure-none\">(.*?)</p>", Pattern.DOTALL);
    private static final Pattern PRINCIPAL_INVESTIGATOR = Pattern.compile(
            "<h4>\\s*Principal Investigator\\s*</h4>\\s*<p>\\s*<strong>Name:</strong>([^<]*)", Pattern.DOTALL);
    private static final Pattern RESEARCH_INSTITUTION = Pattern.compile(
            "<h4>\\s*Research Institution\\s*</h4>\\s*<p>\\s*<strong>Name:</strong>([^<]*)", Pattern.DOTALL);

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);

    private SbirPages() {}

    /** Одна страница выдачи: награды по порядку и сколько всего нашлось. */
    record Listing(List<Row> rows, int shownTo, int total) {

        static Listing empty() {
            return new Listing(List.of(), 0, 0);
        }

        /** Последняя ли это страница выдачи — по счётчику площадки, а не по числу строк. */
        boolean last() {
            return rows.isEmpty() || shownTo >= total;
        }
    }

    /** Строка выдачи: номер награды и год с её метки ({@code null}, если метки нет). */
    record Row(String awardId, Integer year) {}

    static Listing listing(String sourceId, String url, String html) {
        if (html == null) {
            throw notAPage(sourceId, url, "пустой ответ");
        }
        Matcher showing = SHOWING.matcher(html);
        if (!showing.find()) {
            if (NO_RESULTS.matcher(html).find()) {
                return Listing.empty();
            }
            throw notAPage(sourceId, url, "нет ни «Showing … results», ни «No results found.»");
        }
        int shownTo = Integer.parseInt(showing.group(2));
        int total = Integer.parseInt(showing.group(3).replace(",", ""));
        List<Row> rows = new ArrayList<>();
        Matcher link = RESULT_LINK.matcher(html);
        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (link.find()) {
            ids.add(link.group(1));
            starts.add(link.end());
        }
        // Год ищется между ссылкой на награду и следующей ссылкой — в строке этой награды.
        for (int i = 0; i < ids.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            Matcher year = YEAR_TAG.matcher(html).region(starts.get(i), end);
            rows.add(new Row(ids.get(i), year.find() ? Integer.valueOf(year.group(1)) : null));
        }
        if (rows.isEmpty() && total > 0) {
            throw notAPage(sourceId, url, "счётчик выдачи есть, ссылок на награды нет — сменилась вёрстка");
        }
        return new Listing(List.copyOf(rows), shownTo, total);
    }

    /**
     * Страница награды. Поле, которого нет в разметке, остаётся пустым: решать, годится ли такая
     * награда в корпус, — дело нормализатора, и отбракованная запись видна в счётчике прогона.
     */
    static SbirAward award(String awardId, String url, String html, String sourceId, Provenance provenance) {
        String agency = null;
        String branch = null;
        Matcher agencyMatcher = AGENCY.matcher(html);
        if (agencyMatcher.find()) {
            agency = text(agencyMatcher.group(1));
            branch = text(agencyMatcher.group(2));
        }
        String program = null;
        String phase = null;
        Matcher tags = TAGS.matcher(html);
        if (tags.find()) {
            Matcher tag = TAG.matcher(tags.group(1));
            while (tag.find()) {
                String value = text(tag.group(1));
                if (value == null) {
                    continue;
                }
                if (value.equals("SBIR") || value.equals("STTR")) {
                    program = value;
                } else if (value.startsWith("Phase")) {
                    phase = value;
                }
            }
        }
        String awardYear = first(AWARD_YEAR, html);
        return new SbirAward(
                awardId,
                url,
                first(TITLE, html),
                first(COMPANY, html),
                // Условие программы: получатель SBIR/STTR принадлежит американцам и работает в США.
                // Адрес страны не всегда называет («New York, NY, 10022»), а угадывать незачем.
                "US",
                agency,
                branch,
                program,
                phase,
                date(first(START_DATE, html)),
                awardYear == null ? null : Integer.valueOf(awardYear),
                first(ABSTRACT, html),
                first(PRINCIPAL_INVESTIGATOR, html),
                absentIfNa(first(RESEARCH_INSTITUTION, html)),
                sourceId,
                provenance);
    }

    private static String first(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? text(matcher.group(1)) : null;
    }

    /** Текст без разметки и сущностей HTML, пробелы схлопнуты; пустое — {@code null}. */
    static String text(String html) {
        if (html == null) {
            return null;
        }
        String withoutTags = HTML_TAG.matcher(html.replace("<br />", " ").replace("<br>", " "))
                .replaceAll(" ");
        String collapsed = WHITESPACE
                .matcher(HtmlUtils.htmlUnescape(withoutTags))
                .replaceAll(" ")
                .trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, US_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String absentIfNa(String value) {
        return value == null || value.equalsIgnoreCase("N/A") ? null : value;
    }

    private static ConnectorException notAPage(String sourceId, String url, String why) {
        return new ConnectorException.Permanent(
                sourceId, 200, "SBIR.gov вернул не выдачу поиска для %s: %s".formatted(url, why));
    }
}
