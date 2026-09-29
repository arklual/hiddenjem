package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.application.usecase.RadarDigestUseCase;

/**
 * OpenAPI {@code RadarDigest} — one row per tracked direction.
 *
 * <p>A direction that has never been analysed keeps its row with an absent portrait. Dropping it
 * would leave the analyst wondering whether they saved it at all, and "nothing has run here yet" is
 * itself the answer they came for.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RadarDigestView(
        UUID savedDomainId,
        String query,
        UUID reportId,
        TrendReportView.DirectionPortraitView portrait,
        int entered,
        int left,
        List<String> headline,
        Instant analysedAt,
        Instant seenAt,
        /**
         * Отчёт этого направления недоступен этому аналитику.
         *
         * <p>Отличается от «ни разу не анализировали», где пуст {@code reportId}: там анализа не
         * было, здесь он был, а прав на чтение больше нет. Свести их к одному значило бы сказать
         * «тут ничего не считали», когда считали.
         */
        boolean reportUnavailable) {

    public static RadarDigestView from(RadarDigestUseCase.DirectionDigest digest) {
        return new RadarDigestView(
                digest.savedDomainId(),
                digest.query(),
                digest.reportId(),
                digest.portrait() == null ? null : TrendReportView.DirectionPortraitView.from(digest.portrait()),
                digest.entered(),
                digest.left(),
                digest.headline(),
                digest.analysedAt(),
                digest.seenAt(),
                digest.reportUnavailable());
    }
}
