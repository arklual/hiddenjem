package dev.horizon.ingestion.connector.support;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndPerson;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;

import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Разбор поисковой ленты RSS/Atom — общее у Хабра и отраслевых медиа.
 *
 * <p>От коннектора RSS отличается одним, но главным: лента здесь — ответ на запрос, а не последние
 * тридцать записей площадки. Поэтому фильтрации по словам запроса нет — её сделал поиск источника,
 * — а отказ разбора не глотается: пустая лента и лента, которую не удалось прочитать, должны
 * различаться (разбор 101).
 */
public final class SearchFeeds {

    private static final int MAX_EXTERNAL_ID_LENGTH = 200;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private SearchFeeds() {}

    /** Запись поисковой ленты в виде, не зависящем от Rome. */
    public record Entry(
            String title,
            String link,
            String description,
            String content,
            Instant publishedAt,
            List<String> authors,
            String externalId) {}

    /** Заголовок ленты и её записи. */
    public record Feed(String title, List<Entry> entries) {}

    /**
     * Прочитать ленту.
     *
     * @throws ConnectorException.Permanent тело не разбирается как RSS или Atom: площадка ответила
     *     страницей-заглушкой (проверка на робота, капча) вместо ленты. Это отказ, а не ноль записей.
     */
    public static Feed parse(String sourceId, String url, String body) {
        SyndFeedInput input = new SyndFeedInput();
        // Ленты — недоверенный XML из открытой сети: без DTD и раскрытия сущностей.
        input.setAllowDoctypes(false);
        input.setXmlHealerOn(true);
        SyndFeed feed;
        try {
            feed = input.build(new StringReader(withoutControlCharacters(body)));
        } catch (FeedException | IllegalArgumentException e) {
            throw new ConnectorException.Permanent(sourceId, 200, "Cannot parse search feed " + url, e);
        }
        List<Entry> entries = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
            entries.add(new Entry(
                    entry.getTitle() == null ? null : entry.getTitle().trim(),
                    linkOf(entry),
                    entry.getDescription() == null
                            ? null
                            : entry.getDescription().getValue(),
                    entry.getContents().isEmpty()
                            ? null
                            : entry.getContents().get(0).getValue(),
                    date == null ? null : date.toInstant(),
                    authorsOf(entry),
                    externalIdOf(entry)));
        }
        String title = feed.getTitle() == null ? url : feed.getTitle().trim();
        return new Feed(title, entries);
    }

    /**
     * Тело без управляющих символов, запрещённых в XML 1.0.
     *
     * <p>Полный текст статьи в ленте — это то, что автор вставил в редактор, и Robohub на запрос
     * «computer architecture» отдаёт полтора мегабайта с одним байтом {@code 0x03} посреди слова.
     * Разбор такой ленты падает целиком, и из-за одного символа терялось бы всё издание.
     */
    static String withoutControlCharacters(String body) {
        return CONTROL.matcher(body).replaceAll("");
    }

    /** Ссылка без меток кампаний: `?utm_source=…` у одной и той же статьи бывает разным. */
    private static String linkOf(SyndEntry entry) {
        String link = entry.getLink() != null && !entry.getLink().isBlank() ? entry.getLink() : entry.getUri();
        if (link == null) {
            return null;
        }
        int query = link.indexOf("?utm_");
        return (query > 0 ? link.substring(0, query) : link).trim();
    }

    private static List<String> authorsOf(SyndEntry entry) {
        List<String> authors = new ArrayList<>();
        for (SyndPerson person : entry.getAuthors()) {
            if (person.getName() != null && !person.getName().isBlank()) {
                authors.add(person.getName().trim());
            }
        }
        if (authors.isEmpty() && entry.getAuthor() != null && !entry.getAuthor().isBlank()) {
            authors.add(entry.getAuthor().trim());
        }
        return authors;
    }

    private static String externalIdOf(SyndEntry entry) {
        String candidate = linkOf(entry);
        if (candidate == null || candidate.isBlank()) {
            candidate = entry.getUri() == null ? String.valueOf(entry.getTitle()) : entry.getUri();
        }
        String trimmed = candidate.trim();
        return trimmed.length() <= MAX_EXTERNAL_ID_LENGTH ? trimmed : "sha256:" + Hashing.sha256Hex(trimmed);
    }

    /** Есть ли в строке кириллица — то есть спрашивать ли русский источник исходной формулировкой. */
    public static boolean hasCyrillic(String text) {
        return text != null
                && text.chars().anyMatch(ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC);
    }
}
