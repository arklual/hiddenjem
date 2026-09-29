package dev.horizon.ingestion.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-level tunables ({@code horizon.ingestion.*}).
 *
 * <p>A record with defaulting compact-constructor logic rather than field annotations: the defaults
 * are visible in one place and apply identically to tests, which construct this directly.
 *
 * @param pageSize documents written per transaction — also the granularity at which the cursor
 *     advances
 * @param staleRunAfter how long a {@code RUNNING} row may live before it is treated as abandoned; a
 *     killed process must not block a source forever
 * @param lockLease lease of the per-connector Redis lock (data model §5: 10 minutes)
 * @param scheduleEnabled master switch for the incremental scheduler (UC-16)
 * @param incrementalWindowDays how far back a scheduled incremental run looks when a source has no
 *     cursor yet
 */
@ConfigurationProperties(prefix = "horizon.ingestion")
public record IngestionProperties(
        int pageSize,
        Duration staleRunAfter,
        Duration lockLease,
        boolean lockEnabled,
        boolean scheduleEnabled,
        int incrementalWindowDays,
        int scheduledMaxDocuments) {

    public IngestionProperties {
        pageSize = pageSize <= 0 ? 100 : pageSize;
        staleRunAfter = staleRunAfter == null ? Duration.ofMinutes(30) : staleRunAfter;
        lockLease = lockLease == null ? Duration.ofMinutes(10) : lockLease;
        incrementalWindowDays = incrementalWindowDays <= 0 ? 30 : incrementalWindowDays;
        scheduledMaxDocuments = scheduledMaxDocuments <= 0 ? 2000 : scheduledMaxDocuments;
    }

    public static IngestionProperties defaults() {
        return new IngestionProperties(100, Duration.ofMinutes(30), Duration.ofMinutes(10), true, true, 30, 2000);
    }
}
