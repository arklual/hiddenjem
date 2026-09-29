package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI {@code ResearchRequestView} — the single representation of a request, returned by the
 * polling endpoint <em>and</em> carried in every SSE event.
 *
 * <p>One shape for both transports is a deliberate contract decision (ADR-0012): the client can fall
 * back from the stream to polling without any second parser, and the two paths can never disagree
 * about what a request looks like.
 *
 * <p>{@code NON_NULL} inclusion: the nullable fields of the contract are optional, so omitting them
 * is valid, whereas emitting {@code "failure": null} for a healthy request would force every client
 * to null-check a field that has no meaning yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchRequestView(
        UUID id,
        String query,
        String normalizedQuery,
        AnalysisParametersDto parameters,
        String status,
        AnalysisProgressView progress,
        UUID reportId,
        boolean partial,
        boolean fromCache,
        String outcome,
        /**
         * Запустил ли этот запрос тот, кто смотрит (BR-A53).
         *
         * <p>Отсутствует везде, кроме списка: на странице одного запроса вопрос «чей он» не стоит —
         * туда приходят по ссылке на конкретный расчёт.
         */
        Boolean mine,
        /**
         * Кто запустил запрос (BR-A57).
         *
         * <p>Идентификатор, а не имя: имя принадлежит другому ограниченному контексту, и копия здесь
         * устарела бы при первом переименовании. Клиент разрешает его по справочнику организации —
         * а если справочник не ответил, остаётся {@code mine}, которого достаточно, чтобы список
         * работал.
         */
        UUID requestedBy,
        FailureInfoView failure,
        Instant submittedAt,
        Instant finishedAt,
        Long etaSeconds) {}
