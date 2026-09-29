package dev.horizon.ingestion.connector.newswire;

import java.util.Locale;

import dev.horizon.ingestion.connector.newswire.model.PressRelease;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Пресс-релиз → документ.
 *
 * <p><b>Автор — выпустившая организация.</b> Релиз говорит от имени компании, а не журналиста, и
 * правило «две независимые организации» должно видеть именно её: десять релизов одной компании —
 * одно свидетельство, релизы двух компаний — два. Площадка ({@code venue}) — только канал
 * распространения; модуль доверенности аналитики узнаёт её по адресу и понижает вес — так и
 * задумано: релиз — первичный индикатор с точной датой, а не подтверждение.
 *
 * <p>Рубрик площадки (у GlobeNewswire — {@code dc:keyword}) документ не получает: метка, не
 * совпавшая с направлением, записывает документ в чужое направление (разбор 80).
 */
public class PressReleaseNormalizer implements DocumentNormalizer<PressRelease> {

    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(PressRelease release) {
        if (release.publishedOn() == null) {
            throw new IllegalArgumentException("Press release without a publication date: " + release.url());
        }
        var builder = Document.builder()
                .externalRef(release.sourceId(), release.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(release.title())
                .abstractText(truncate(release.summary()))
                .language(language(release.language()))
                .publishedOn(release.publishedOn())
                .url(release.url())
                .venue(new Venue(release.wire(), "PRESS_RELEASE", null))
                .provenance(release.provenance());
        String issuer = release.issuer();
        if (issuer != null && !issuer.isBlank()) {
            builder.author(new Author(issuer, null, issuer, Issuers.typeOf(issuer), null));
        } else {
            builder.author(Author.of(release.wire()));
        }
        return builder.build();
    }

    /** {@code zh-hant} → {@code zh}: документ хранит код языка без письменности и региона. */
    static String language(String code) {
        if (code == null || code.isBlank()) {
            return "en";
        }
        String primary = code.trim().toLowerCase(Locale.ROOT);
        int dash = primary.indexOf('-');
        return dash > 0 ? primary.substring(0, dash) : primary;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
