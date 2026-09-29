package dev.horizon.trends.adapter.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.config.ResearchProperties;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;

/**
 * Read-through cache for finished reports (ADR-0011).
 *
 * <p>Reports are immutable, so this cache has no invalidation problem — only expiry. That is what
 * makes it safe to serve a hit without any freshness check and is the main reason the read path fits
 * its 300 ms budget (NFR-P1).
 *
 * <p><b>Fails soft, always.</b> Every method swallows backend failures: a cache outage may make the
 * system slower, but it must never make it wrong or unavailable. A miss and an error are therefore
 * the same thing to the caller, and the only visible difference is a WARN in the log.
 */
public class RedisReportCache implements ReportCache {

    private static final Logger log = LoggerFactory.getLogger(RedisReportCache.class);

    public static final String KEY_PREFIX = "report:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisReportCache(StringRedisTemplate redis, ObjectMapper objectMapper, ResearchProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = properties.cacheTtl();
    }

    @Override
    public Optional<TrendReport> get(TrendReportId id) {
        try {
            String json = redis.opsForValue().get(key(id));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(json, ReportSnapshot.class).toDomain());
        } catch (Exception e) {
            // Includes a stale entry written by a previous schema: treating it as a miss lets the
            // database answer and the entry is overwritten on the way back.
            log.warn("Кэш отчётов недоступен или содержит несовместимое значение ({}), читаем из БД", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(TrendReport report) {
        try {
            redis.opsForValue()
                    .set(key(report.id()), objectMapper.writeValueAsString(ReportSnapshot.from(report)), ttl);
        } catch (Exception e) {
            log.warn("Не удалось записать отчёт {} в кэш: {}", report.id(), e.getMessage());
        }
    }

    @Override
    public void evict(TrendReportId id) {
        try {
            redis.delete(key(id));
        } catch (Exception e) {
            log.warn("Не удалось удалить отчёт {} из кэша: {}", id, e.getMessage());
        }
    }

    private static String key(TrendReportId id) {
        return KEY_PREFIX + id.value();
    }
}
