package dev.horizon.ingestion.connector.newswire.model;

import java.time.LocalDate;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Пресс-релиз так, как его отдала лента или страница результатов ленты новостей.
 *
 * @param wire площадка распространения («GlobeNewswire», «PR Newswire») — название площадки, а не
 *     издания: релиз пишет компания, площадка его только рассылает
 * @param issuer компания или организация, выпустившая релиз; {@code null}, если площадка её не назвала
 * @param language код языка, как его указала площадка; {@code null} — не указан
 */
public record PressRelease(
        String sourceId,
        String externalId,
        String wire,
        String title,
        String url,
        LocalDate publishedOn,
        String summary,
        String issuer,
        String language,
        Provenance provenance)
        implements RawDocument {}
