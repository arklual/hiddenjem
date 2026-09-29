package dev.horizon.trends.adapter.web.dto;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.application.usecase.RefreshRadarUseCase;

/**
 * OpenAPI {@code RadarRefresh} — what happened to each direction.
 *
 * <p>Three of the four outcomes are not failures, so the response is 200 even when nothing was
 * queued: the list of outcomes *is* the answer, and an HTTP error would leave the client guessing
 * which direction met which fate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RadarRefreshView(UUID savedDomainId, String query, String outcome, UUID requestId, String reason) {

    public static List<RadarRefreshView> from(List<RefreshRadarUseCase.RefreshResult> results) {
        return results.stream()
                .map(result -> new RadarRefreshView(
                        result.savedDomainId(),
                        result.query(),
                        result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        result.requestId(),
                        result.reason()))
                .toList();
    }
}
