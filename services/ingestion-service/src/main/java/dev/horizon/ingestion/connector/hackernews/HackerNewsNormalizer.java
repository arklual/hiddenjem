package dev.horizon.ingestion.connector.hackernews;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.horizon.ingestion.connector.hackernews.model.HackerNewsResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для Hacker News.
 *
 * <p><b>Ссылка — страница обсуждения, а не статья, на которую оно ссылается.</b> Документ здесь —
 * само обсуждение, и доверенность оценивается по нему: агрегатор, пониженная. Ссылка на статью
 * уходит в текст, чтобы аналитик мог дойти до первоисточника, но первоисточником обсуждение не
 * становится.
 */
public class HackerNewsNormalizer implements DocumentNormalizer<HackerNewsResponse.Raw> {

    @Override
    public Document normalize(HackerNewsResponse.Raw raw) {
        HackerNewsResponse.Hit hit = raw.hit();
        if (hit.title() == null || hit.title().isBlank()) {
            throw new IllegalArgumentException("Hacker News story without a title: " + hit.objectId());
        }
        if (hit.createdAt() == null || hit.createdAt().isBlank()) {
            throw new IllegalArgumentException("Hacker News story without a date: " + hit.objectId());
        }
        String text = hit.storyText();
        if (hit.url() != null && !hit.url().isBlank()) {
            text = (text == null || text.isBlank() ? "" : text + " ") + "Ссылка обсуждения: " + hit.url().trim();
        }
        Map<String, Number> extra = new LinkedHashMap<>();
        if (hit.points() != null) {
            extra.put("points", hit.points());
        }
        if (hit.numComments() != null) {
            extra.put("comments", hit.numComments());
        }
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(hit.title().trim())
                .abstractText(text)
                .language("en")
                .publishedOn(LocalDate.ofInstant(Instant.parse(hit.createdAt().trim()), ZoneOffset.UTC))
                .url("https://news.ycombinator.com/item?id=" + hit.objectId())
                .venue(new Venue("Hacker News", "AGGREGATOR", null))
                .metrics(new DocumentMetrics(null, null, null, extra))
                .provenance(raw.provenance());
        if (hit.author() != null && !hit.author().isBlank()) {
            builder.author(new Author(hit.author().trim(), null, null, null, null));
        }
        return builder.build();
    }
}
