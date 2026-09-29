package dev.horizon.trends.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.horizon.trends.domain.research.AnalysisMode;

/**
 * Tunables of the research process.
 *
 * <p>Externalised rather than hard-coded because they encode product policy (how stale a reused
 * result may be, how long an analysis is allowed to run) that operations must be able to adjust
 * without a release.
 */
@ConfigurationProperties(prefix = "horizon.research")
public record ResearchProperties(
        /** Срок саги в быстром режиме — весь анализ в срок ТЗ ({@code HORIZON_SAGA_TIMEOUT}). */
        Duration sagaTimeout,
        Duration resultTtl,
        Duration cacheTtl,
        int maxDocuments,
        int userQuotaPerHour,
        int organizationQuotaPerHour,
        int timeoutSweepBatchSize,
        /**
         * Срок саги в качественном режиме ({@code HORIZON_SAGA_TIMEOUT_QUALITY}).
         *
         * <p>Отдельной настройкой, а не множителем быстрого: сбор в качественном режиме читает
         * больше по своим бюджетам, и срок саги обязан их покрывать — иначе запрос закроет таймаут,
         * пока сбор ещё честно работает. Бюджеты и срок настраиваются вместе, одним решением.
         */
        Duration sagaTimeoutQuality) {

    public ResearchProperties {
        sagaTimeout = sagaTimeout == null ? Duration.ofMinutes(20) : sagaTimeout;
        sagaTimeoutQuality = sagaTimeoutQuality == null ? Duration.ofMinutes(40) : sagaTimeoutQuality;
        resultTtl = resultTtl == null ? Duration.ofHours(24) : resultTtl;
        cacheTtl = cacheTtl == null ? Duration.ofHours(24) : cacheTtl;
        maxDocuments = maxDocuments <= 0 ? 5000 : maxDocuments;
        userQuotaPerHour = userQuotaPerHour <= 0 ? 20 : userQuotaPerHour;
        organizationQuotaPerHour = organizationQuotaPerHour <= 0 ? 200 : organizationQuotaPerHour;
        timeoutSweepBatchSize = timeoutSweepBatchSize <= 0 ? 100 : timeoutSweepBatchSize;
    }

    /** Срок, отведённый запросу в данном режиме. */
    public Duration sagaTimeout(AnalysisMode mode) {
        return mode == AnalysisMode.QUALITY ? sagaTimeoutQuality : sagaTimeout;
    }
}
