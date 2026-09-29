package dev.horizon.trends.adapter.observability;

import org.springframework.stereotype.Component;

import dev.horizon.trends.application.port.ResearchMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer implementation of {@link ResearchMetrics}.
 *
 * <p>The names are not incidental: {@code horizon_research_requests_total},
 * {@code horizon_research_requests_partial_total} and {@code horizon_trends_produced_total} are what
 * the alerting rules already ask for. The rule {@code HorizonPartialRunRatioHigh} divides the second
 * by the first, which is why "partial" is a separate counter and not a tag — a ratio over a tagged
 * counter has to sum the untagged total, and that is one more thing to get subtly wrong in a query
 * nobody re-reads.
 */
@Component
public class MicrometerResearchMetrics implements ResearchMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter partialRequests;
    private final Counter trendsProduced;

    public MicrometerResearchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.partialRequests = Counter.builder("horizon.research.requests.partial")
                .description("Reports produced from a corpus that was missing at least one source")
                .register(meterRegistry);
        this.trendsProduced = Counter.builder("horizon.trends.produced")
                .description("Ranked trends delivered to analysts")
                .register(meterRegistry);
    }

    @Override
    public void reportPublished(int trendCount, boolean partial) {
        finished("completed");
        if (partial) {
            partialRequests.increment();
        }
        trendsProduced.increment(trendCount);
    }

    @Override
    public void requestFailed() {
        finished("failed");
    }

    @Override
    public void requestCancelled() {
        finished("cancelled");
    }

    /** Тег `result` объявлен в шапке `alerts.yml` как часть контракта по метрикам. */
    private void finished(String result) {
        meterRegistry.counter("horizon.research.requests", "result", result).increment();
    }
}
