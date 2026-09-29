package dev.horizon.ingestion.connector.industry;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.industry.model.IndustryArticle;
import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Статья отраслевого издания → документ.
 *
 * <p>Издание — организация, стоящая за публикацией: десять заметок одного издания остаются одним
 * свидетельством, а две заметки разных изданий — двумя независимыми (BRULE-1). Текст — анонс
 * WordPress; если он короче абзаца, берётся начало полного текста: у части изданий анонс — одна
 * строка, и терминам в ней не из чего сложиться.
 *
 * <p>Рубрик WordPress («News», «Business», «Robotics») документ не получает — по той же причине,
 * что и публикации Хабра: метка, не совпавшая с направлением, записывает документ в чужое
 * направление (разбор 80).
 */
public class IndustryMediaNormalizer implements DocumentNormalizer<IndustryArticle> {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern READ_MORE = Pattern.compile(
            "(The post .{0,300} appeared first on .{0,120}\\.?$)|(\\[(?:…|\\.\\.\\.|&#8230;)\\])|(Continue reading.*$)",
            Pattern.CASE_INSENSITIVE);
    private static final int MIN_ABSTRACT_LENGTH = 300;
    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(IndustryArticle article) {
        SearchFeeds.Entry entry = article.entry();
        if (entry.publishedAt() == null) {
            throw new IllegalArgumentException("Industry article without a publication date: " + entry.link());
        }
        String summary = clean(entry.description());
        if (summary == null || summary.length() < MIN_ABSTRACT_LENGTH) {
            String content = clean(entry.content());
            if (content != null && (summary == null || content.length() > summary.length())) {
                summary = content;
            }
        }
        var builder = Document.builder()
                .externalRef(article.sourceId(), article.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(clean(entry.title()))
                .abstractText(truncate(summary))
                .language("en")
                .publishedOn(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))
                .url(entry.link())
                .venue(Venue.orNull(article.outlet(), "NEWS_OUTLET", null))
                .provenance(article.provenance());
        for (String author : entry.authors()) {
            builder.author(new Author(author, null, article.outlet(), null, null));
        }
        if (entry.authors().isEmpty()) {
            builder.author(new Author(article.outlet(), null, article.outlet(), null, null));
        }
        return builder.build();
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = HTML_TAG.matcher(value)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#8217;", "'")
                .replace("&#8216;", "'")
                .replace("&#8220;", "\"")
                .replace("&#8221;", "\"")
                .replace("&#8211;", "–")
                .replace("&#8212;", "—")
                .replace("&#39;", "'");
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        collapsed = READ_MORE.matcher(collapsed).replaceAll("").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
