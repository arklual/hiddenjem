package dev.horizon.ingestion.connector.regulators;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор перечня проектов Инновационного центра BIS и страницы проекта.
 *
 * <p><b>Перечень</b> — {@code /about/innovation-hub/projects?page=N}, по десять карточек на страницу:
 * ссылка {@code /project/<slug>}, дата и название. Старый адрес {@code /about/bisih/projects.htm}
 * перенаправляет сюда. Поиска у перечня нет, а {@code /search/} запрещён в {@code robots.txt} BIS.
 *
 * <p><b>Дата.</b> На странице проекта одна дата — {@code article:published_time}, она же «Last
 * updated» в боковой колонке и дата карточки в перечне: день последней публикации страницы
 * проекта (отчёт, новая фаза, смена статуса). Даты запуска на странице нет: Agorá объявлен в 2024
 * году, а его страница датирована 27.05.2026 — днём выхода итогового отчёта. Берётся она, и это
 * безопасно для бэктеста: дата не раньше, чем сведения на странице стали публичными, — значит,
 * документ не попадёт в срез, когда его содержания ещё не было (ловушка возраста метки).
 *
 * <p><b>Организации.</b> Сам Инновационный центр и центральные банки из поля «Partners» — у
 * Mandala их семь. Это действительно независимые участники проекта, а не перепечатки.
 */
final class BisProjects {

    private static final Pattern CARD = Pattern.compile(
            "<a href=\"(/project/[^\"?#]+)\" class=\"card-link\">.*?card-date[^>]*>([^<]+)<.*?card-heading\">([^<]+)<",
            Pattern.DOTALL);
    private static final Pattern PUBLISHED =
            Pattern.compile("<meta property=\"article:published_time\" content=\"([^\"]+)\"");
    private static final Pattern DESCRIPTION = Pattern.compile("<meta name=\"description\" content=\"([^\"]*)\"");
    private static final Pattern HEADING =
            Pattern.compile("<h1[^>]*hero-publication__heading[^>]*>\\s*<span>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern TEXT_BLOCK =
            Pattern.compile("<div class=\"text__component\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern BADGE =
            Pattern.compile("<span[^>]*class=\"badge[^\"]*\"[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final DateTimeFormatter CARD_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter META_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");

    private BisProjects() {}

    /** Карточка перечня: адрес страницы проекта, её дата и название. */
    record Card(URI url, LocalDate date, String title) {}

    /** Страница проекта в разобранном виде. */
    record Project(
            String title,
            String description,
            LocalDate publishedOn,
            String status,
            List<String> centres,
            List<String> partners,
            String text) {}

    static List<Card> cards(URI listing, String html) {
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = CARD.matcher(html);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (!seen.add(path)) {
                continue;
            }
            LocalDate date = parseCardDate(matcher.group(2).trim());
            cards.add(new Card(listing.resolve(path), date, HtmlText.of(matcher.group(3))));
        }
        return cards;
    }

    /**
     * Страница проекта; {@code null}, если это не она — нет заголовка или даты публикации.
     *
     * @param fallbackDate дата карточки перечня: на случай, если метатега не окажется
     */
    static Project project(String html, LocalDate fallbackDate) {
        Matcher heading = HEADING.matcher(html);
        String title = heading.find() ? HtmlText.of(heading.group(1)) : null;
        Matcher published = PUBLISHED.matcher(html);
        LocalDate date = published.find() ? parseMetaDate(published.group(1)) : null;
        if (date == null) {
            date = fallbackDate;
        }
        if (title == null || date == null) {
            return null;
        }
        Matcher description = DESCRIPTION.matcher(html);
        String summary = description.find() ? HtmlText.of(description.group(1)) : null;
        StringBuilder text = new StringBuilder();
        Matcher block = TEXT_BLOCK.matcher(html);
        while (block.find()) {
            String paragraph = HtmlText.of(block.group(1));
            if (paragraph != null) {
                text.append(text.isEmpty() ? "" : " ").append(paragraph);
            }
        }
        return new Project(
                title,
                summary,
                date,
                sidebarText(html, "Status"),
                sidebarBadges(html, "Centre"),
                sidebarBadges(html, "Partners"),
                text.isEmpty() ? null : text.toString());
    }

    /** Значение поля боковой колонки — простой текст после заголовка {@code <h6>}. */
    private static String sidebarText(String html, String heading) {
        String section = sidebarSection(html, heading);
        return section == null ? null : HtmlText.of(section);
    }

    /** Значения поля боковой колонки, оформленные метками: центры и партнёры. */
    private static List<String> sidebarBadges(String html, String heading) {
        String section = sidebarSection(html, heading);
        if (section == null) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        Matcher badge = BADGE.matcher(section);
        while (badge.find()) {
            String value = HtmlText.of(badge.group(1));
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String sidebarSection(String html, String heading) {
        String marker = "publication-sidebar__heading\">" + heading + "</h6>";
        int start = html.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = html.indexOf("<h6", start);
        int column = html.indexOf("col-md-7", start);
        if (end < 0 || (column >= 0 && column < end)) {
            end = column;
        }
        if (end < 0) {
            return null;
        }
        // Закрывающий </div> колонки остаётся в отрезке — теги всё равно отбрасываются.
        return html.substring(start, end);
    }

    private static LocalDate parseCardDate(String value) {
        try {
            return LocalDate.parse(value, CARD_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseMetaDate(String value) {
        try {
            return OffsetDateTime.parse(value.trim(), META_DATE).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
