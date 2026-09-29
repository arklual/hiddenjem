package dev.horizon.trends.domain.report;

import java.time.Instant;
import java.util.UUID;

/**
 * Где именно тема встретилась (BR-A69).
 *
 * <p>Отдельная запись, а не поле темы: одна и та же тема живёт в нескольких отчётах, и вопрос
 * аналитика — не «есть ли она», а «где и когда». Ответ обязан быть проверяемым, поэтому вхождение
 * несёт отчёт, его версию и место в рейтинге, а не только факт совпадения.
 *
 * @param normalizedQuery направление в нормализованной форме — по нему считаются направления,
 *     потому что пересчёт создаёт новый запрос, а направление остаётся тем же
 * @param query формулировка направления, как её написал человек
 */
public record TopicOccurrence(
        String trendKey,
        String title,
        int rank,
        UUID reportId,
        int version,
        String query,
        String normalizedQuery,
        Instant generatedAt) {}
