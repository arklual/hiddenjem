package dev.horizon.ingestion.connector.gdelt;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import dev.horizon.ingestion.connector.gdelt.model.GdeltResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для GDELT.
 *
 * <p>У статьи GDELT есть только заголовок, адрес, дата обнаружения, домен и язык — текста нет.
 * Этого достаточно для того, ради чего источник нужен: медийная видимость по годам, домены как
 * разные свидетельства и язык оригинала, который ТЗ требует показывать. Дата — день, когда GDELT
 * увидел статью: это ближайшее к дате публикации, что источник знает.
 */
public class GdeltNormalizer implements DocumentNormalizer<GdeltResponse.Raw> {

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("english", "en"),
            Map.entry("russian", "ru"),
            Map.entry("german", "de"),
            Map.entry("french", "fr"),
            Map.entry("spanish", "es"),
            Map.entry("chinese", "zh"),
            Map.entry("japanese", "ja"),
            Map.entry("korean", "ko"),
            Map.entry("italian", "it"),
            Map.entry("portuguese", "pt"));

    @Override
    public Document normalize(GdeltResponse.Raw raw) {
        GdeltResponse.Article article = raw.article();
        if (article.title() == null || article.title().isBlank()) {
            throw new IllegalArgumentException("GDELT article without a title: " + article.url());
        }
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(article.title().trim())
                .publishedOn(seenOn(article.seendate()))
                .url(article.url().trim())
                .venue(Venue.orNull(article.domain(), "NEWS_OUTLET", null))
                .provenance(raw.provenance());
        String language = article.language() == null
                ? null
                : LANGUAGES.get(article.language().trim().toLowerCase(Locale.ROOT));
        if (language != null) {
            builder.language(language);
        }
        return builder.build();
    }

    /** {@code 20240102T101500Z} → 2024-01-02. */
    static LocalDate seenOn(String seendate) {
        if (seendate == null || seendate.length() < 8 || !seendate.substring(0, 8).chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("GDELT article without a date: " + seendate);
        }
        return LocalDate.of(
                Integer.parseInt(seendate.substring(0, 4)),
                Integer.parseInt(seendate.substring(4, 6)),
                Integer.parseInt(seendate.substring(6, 8)));
    }
}
