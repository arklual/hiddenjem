package dev.horizon.ingestion.domain.source;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.platform.common.util.Guards;

/**
 * A configured data source — aggregate root owning the operational policy for one connector.
 *
 * <p>The connector code is deployed; the <em>policy</em> (enabled, rate limit, credentials
 * requirement, authority weight) is data, so a researcher can throttle or disable a misbehaving
 * source without a release (UC-11).
 *
 * <p>{@code authorityWeight} is a prior on evidence quality: a peer-reviewed article outranks a news
 * item when the report picks which sources to show. It lives here rather than in the analysis engine
 * because it is a property of the source, not of the methodology.
 */
public final class Source {

    public static final int MIN_RATE_LIMIT_PER_MINUTE = 1;
    public static final int MAX_RATE_LIMIT_PER_MINUTE = 6000;

    private final String id;
    private final String displayName;
    private final SourceClass sourceClass;
    private final String baseUrl;
    private final boolean requiresApiKey;
    private final Map<String, Object> config;
    private final double authorityWeight;

    private boolean enabled;
    private int rateLimitPerMinute;
    private long version;

    private Source(
            String id,
            String displayName,
            SourceClass sourceClass,
            boolean enabled,
            String baseUrl,
            int rateLimitPerMinute,
            boolean requiresApiKey,
            Map<String, Object> config,
            double authorityWeight,
            long version) {
        this.id = Guards.requireText(id, "source.id");
        Guards.requireArgument(id.length() <= 48, "source.id must not exceed 48 characters");
        this.displayName = Guards.requireText(displayName, "source.displayName");
        this.sourceClass = Guards.requireNonNull(sourceClass, "source.sourceClass");
        this.enabled = enabled;
        this.baseUrl = Guards.requireText(baseUrl, "source.baseUrl");
        this.rateLimitPerMinute = checkRateLimit(rateLimitPerMinute);
        this.requiresApiKey = requiresApiKey;
        this.config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        this.authorityWeight = Guards.requireRange(authorityWeight, "source.authorityWeight", 0.0, 1.0);
        this.version = version;
    }

    public static Source of(
            String id,
            String displayName,
            SourceClass sourceClass,
            boolean enabled,
            String baseUrl,
            int rateLimitPerMinute,
            boolean requiresApiKey,
            Map<String, Object> config,
            double authorityWeight,
            long version) {
        return new Source(
                id,
                displayName,
                sourceClass,
                enabled,
                baseUrl,
                rateLimitPerMinute,
                requiresApiKey,
                config,
                authorityWeight,
                version);
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void changeEnabled(Boolean value) {
        if (value != null) {
            this.enabled = value;
        }
    }

    /**
     * Changes the polite-crawling budget. Bounded by the contract
     * ({@code UpdateSourceRequest.rateLimitPerMinute}) and by common sense: a rate limit of zero
     * would be a disabled source expressed in a way nothing else understands.
     */
    public void changeRateLimit(Integer perMinute) {
        if (perMinute != null) {
            this.rateLimitPerMinute = checkRateLimit(perMinute);
        }
    }

    private static int checkRateLimit(int perMinute) {
        return Guards.requireRange(
                perMinute, "source.rateLimitPerMinute", MIN_RATE_LIMIT_PER_MINUTE, MAX_RATE_LIMIT_PER_MINUTE);
    }

    /**
     * A source that needs credentials but has none is <em>unavailable</em>, not broken: the run
     * reports it in {@code unavailableSources} and carries on (BR-C7).
     */
    public boolean isUsable(boolean credentialsConfigured) {
        return enabled && (!requiresApiKey || credentialsConfigured);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public SourceClass sourceClass() {
        return sourceClass;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public int rateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public Map<String, Object> config() {
        return config;
    }

    public double authorityWeight() {
        return authorityWeight;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Source other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Source[%s enabled=%s rate=%d/min]".formatted(id, enabled, rateLimitPerMinute);
    }
}
