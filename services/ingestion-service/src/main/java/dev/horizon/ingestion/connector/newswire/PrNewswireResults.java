package dev.horizon.ingestion.connector.newswire;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.support.ConnectorException;

/**
 * Разбор страницы результатов поиска PR Newswire ({@code /search/news/}).
 *
 * <p>Страница собирается на сервере: каждый результат — блок {@code <div class="row newsCards">} с
 * датой ({@code <small>Sep 28, 2026, 08:32 ET</small>}), ссылкой на релиз
 * ({@code <a class="news-release" href="/news-releases/<slug>-<id>.html">}), фрагментом текста вокруг
 * совпадения и строкой «More news about: <компания>». Разбирается регулярными выражениями, а не
 * библиотекой HTML: нужны пять полей из повторяющегося блока, и библиотеки ради них в модуле нет.
 *
 * <p><b>Три исхода, а не два.</b> Страница с карточками — результат; страница с пометкой
 * {@code search-results-text__no-results} — законный ноль; страница без того и другого (проверка на
 * робота, заглушка, смена вёрстки) — отказ. Смену вёрстки нельзя принимать за «релизов нет»: это
 * тот самый молча пустой признак, который выглядит как ноль (разбор 101).
 */
final class PrNewswireResults {

    private static final String CARD = "<div class=\"row newsCards\"";
    private static final String NO_RESULTS = "search-results-text__no-results";
    private static final Pattern LANG = Pattern.compile("^\\s*lang=\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile("<small>([^<]+)</small>");
    private static final Pattern RELEASE =
            Pattern.compile("<a class=\"news-release\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern ISSUER = Pattern.compile("More news about:\\s*<a href=\"[^\"]*\">([^<]+)</a>");
    private static final Pattern RELEASE_ID = Pattern.compile("-(\\d{6,})\\.html$");
    /** Время дано по Нью-Йорку («ET»); дата релиза — та, что напечатана, без пересчёта в UTC. */
    private static final DateTimeFormatter PRINTED_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    /** Карточка результата. {@code publishedOn == null} — дату не удалось прочитать. */
    record Card(
            String externalId,
            String title,
            String url,
            LocalDate publishedOn,
            String teaser,
            String issuer,
            String language) {}

    /** Карточки страницы; {@code empty} — площадка прямо сказала «ничего не найдено». */
    record Page(List<Card> cards, boolean saidNothingFound) {}

    private PrNewswireResults() {}

    static Page parse(String sourceId, URI page, String body) {
        String html = body == null ? "" : body;
        String[] blocks = html.split(Pattern.quote(CARD));
        List<Card> cards = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            Card card = card(page, blocks[i]);
            if (card != null) {
                cards.add(card);
            }
        }
        if (blocks.length > 1 && cards.isEmpty()) {
            throw new ConnectorException.Permanent(
                    sourceId, 200, "PR Newswire result cards on " + page + " no longer parse: layout changed?");
        }
        if (cards.isEmpty() && !html.contains(NO_RESULTS)) {
            throw new ConnectorException.Permanent(
                    sourceId, 200, "PR Newswire answered " + page + " with neither results nor a no-results notice");
        }
        return new Page(cards, cards.isEmpty());
    }

    private static Card card(URI page, String block) {
        Matcher release = RELEASE.matcher(block);
        if (!release.find()) {
            return null;
        }
        String href = HtmlText.unescape(release.group(1).trim());
        String url = page.resolve(href).toString();
        String title = HtmlText.plain(release.group(2));
        if (title == null) {
            return null;
        }
        Matcher date = DATE.matcher(block);
        LocalDate published = date.find() ? date(date.group(1)) : null;
        String teaser = null;
        Matcher paragraph = PARAGRAPH.matcher(block);
        paragraph.region(release.end(), block.length());
        while (paragraph.find()) {
            String text = HtmlText.plain(paragraph.group(1));
            if (text != null && !text.startsWith("More news about")) {
                teaser = text;
                break;
            }
        }
        Matcher issuer = ISSUER.matcher(block);
        Matcher lang = LANG.matcher(block);
        Matcher id = RELEASE_ID.matcher(href);
        return new Card(
                id.find() ? id.group(1) : url,
                title,
                url,
                published,
                teaser,
                issuer.find() ? HtmlText.plain(issuer.group(1)) : null,
                lang.find() ? lang.group(1) : null);
    }

    /** {@code Sep 28, 2026, 08:32 ET} → 2026-09-28. */
    static LocalDate date(String printed) {
        String value = HtmlText.plain(printed);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return LocalDate.parse(parts[0].trim() + ", " + parts[1].trim(), PRINTED_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
