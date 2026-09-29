package dev.horizon.ingestion.connector.rss;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.rss.model.RssItem;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL for RSS/Atom news.
 *
 * <p>News is the weakest evidence class in the corpus and is treated accordingly: HTML is stripped
 * from summaries (feeds ship markup, tracking pixels and "read more" links), the publication instant
 * becomes a plain date, and the feed title becomes the venue so that ten items from one outlet do
 * not masquerade as ten independent signals.
 */
public class RssNormalizer implements DocumentNormalizer<RssItem> {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(RssItem item) {
        if (item.publishedAt() == null) {
            throw new IllegalArgumentException("Feed entry without a publication date: " + item.link());
        }
        var builder = Document.builder()
                .externalRef(item.sourceId(), item.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(cleanText(item.title()))
                .abstractText(truncate(cleanText(item.description())))
                .publishedOn(LocalDate.ofInstant(item.publishedAt(), ZoneOffset.UTC))
                .url(item.link())
                .venue(Venue.orNull(item.feedTitle(), "NEWS_OUTLET", null))
                .provenance(item.provenance());

        for (String author : item.authors()) {
            if (author != null && !author.isBlank()) {
                builder.author(new Author(author, null, item.feedTitle(), null, null));
            }
        }
        if (item.authors().isEmpty() && item.feedTitle() != null) {
            // An unsigned article is still attributable to its outlet, and the evidence card needs
            // something to show.
            builder.author(Author.of(item.feedTitle()));
        }
        for (String category : item.categories()) {
            if (category != null && !category.isBlank()) {
                builder.topic(DocumentTopic.of(category, category, null));
            }
        }
        return builder.build();
    }

    static String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String withoutTags = HTML_TAG.matcher(value).replaceAll(" ");
        String unescaped = withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        String collapsed = WHITESPACE.matcher(unescaped).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
