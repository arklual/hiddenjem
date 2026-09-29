package dev.horizon.ingestion.connector.regulators;

import dev.horizon.ingestion.connector.regulators.model.RegulatorNotice;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Запись регулятора → документ класса {@code NEWS}.
 *
 * <p>Автор — организация, стоящая за записью, а не человек: у проекта BIS — Инновационный центр и
 * центральные банки-партнёры, у фирмы песочницы FCA — сама фирма ({@code COMPANY}), у события Банка
 * России — Банк России. Правило «две независимые организации» и оценка доверенности видят их так же,
 * как аффилиацию у статьи; десять событий Банка России остаются одной организацией.
 *
 * <p>Рубрик документ не получает — по той же причине, что публикации Хабра и отраслевых медиа: метка,
 * не совпавшая с направлением, записывает документ в чужое направление (разбор 80).
 */
public class RegulatorsNormalizer implements DocumentNormalizer<RegulatorNotice> {

    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(RegulatorNotice notice) {
        if (notice.publishedOn() == null) {
            throw new IllegalArgumentException("Regulator record without a date: " + notice.url());
        }
        var builder = Document.builder()
                .externalRef(notice.sourceId(), notice.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(notice.title())
                .abstractText(truncate(notice.text()))
                .language(notice.language())
                .publishedOn(notice.publishedOn())
                .url(notice.url())
                .venue(Venue.orNull(notice.venue(), "REGULATOR", null))
                .provenance(notice.provenance());
        for (RegulatorNotice.Organization organization : notice.organizations()) {
            builder.author(new Author(
                    organization.name(), null, organization.name(), organization.type(), organization.country()));
        }
        return builder.build();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
