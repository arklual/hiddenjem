package dev.horizon.ingestion.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the outbound side ({@code horizon.connectors.*}).
 *
 * <p>Everything a source needs to be crawled politely and legally lives here: the contact-bearing
 * User-Agent every request must carry (NFR-S12, BR-C3), timeouts, the retry/circuit-breaker policy
 * and per-source endpoints, page sizes, credentials and rate limits.
 *
 * <p>Credentials are read from the environment ({@code ${HORIZON_ALPHAXIV_API_KEY:}}), never committed —
 * an absent key makes the source report itself unavailable, which the collection handles as a normal
 * degraded case rather than as an error.
 */
@ConfigurationProperties(prefix = "horizon.connectors")
public record ConnectorsProperties(
        String userAgent,
        String contactEmail,
        Duration connectTimeout,
        Duration responseTimeout,
        int maxResponseBytes,
        Resilience resilience,
        Map<String, ConnectorSettings> sources) {

    public ConnectorsProperties {
        userAgent = blankTo(userAgent, "Horizon/1.0");
        contactEmail = blankTo(contactEmail, "horizon@example.org");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(30) : responseTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 16 * 1024 * 1024 : maxResponseBytes;
        resilience = resilience == null ? Resilience.defaults() : resilience;
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public static ConnectorsProperties defaults() {
        return new ConnectorsProperties(null, null, null, null, 0, null, Map.of());
    }

    /**
     * Whether the contact address is still the placeholder nobody can be reached at.
     *
     * <p>Crossref and OpenAlex route identified traffic to a faster "polite pool" and throttle the
     * rest. The default here is a placeholder domain, and a deployment that never sets a real
     * address keeps sending it — which reads to those services as an unidentified caller. The
     * symptom is not a broken build but HTTP 429 hours later, on a machine nobody is watching, and
     * an ingestion run that quietly collects nothing.
     *
     * <p>Kept as a default rather than a required field on purpose: a local run must not need a
     * mailbox. The trade is that the default has to announce itself, which is what the startup
     * warning does.
     *
     * <p>Признак — метка {@code example} в любой части домена, а не список суффиксов. Список был
     * первым и молчал ровно там, где заглушка встречается чаще всего: {@code ops@horizon.example}
     * из compose и базового чарта под {@code example.org/.com/.bank} не подходит. Метка
     * {@code example} зарезервирована именно под примеры (RFC 2606), и любое её появление в домене
     * означает адрес, по которому никто не ответит.
     */
    public boolean contactEmailIsPlaceholder() {
        int at = contactEmail.lastIndexOf('@');
        String domain = at < 0 ? contactEmail : contactEmail.substring(at + 1);
        for (String label : domain.toLowerCase(java.util.Locale.ROOT).split("\\.")) {
            if (label.equals("example")) {
                return true;
            }
        }
        return false;
    }

    /** Settings for one source, falling back to empty defaults when the key is absent. */
    public ConnectorSettings settings(String sourceId) {
        return sources.getOrDefault(sourceId, ConnectorSettings.empty());
    }

    /**
     * The complete {@code User-Agent} header: product, version and a way to reach a human.
     *
     * <p>Sources block anonymous crawlers, and several (Crossref, OpenAlex) give a faster "polite
     * pool" to requests that identify themselves. Compliance and performance point the same way.
     */
    public String fullUserAgent() {
        return "%s (+https://horizon.dev; mailto:%s)".formatted(userAgent, contactEmail);
    }

    /**
     * Retry and circuit-breaker policy.
     *
     * @param maxAttempts total attempts including the first
     * @param initialBackoff first wait
     * @param backoffMultiplier exponential growth factor
     * @param jitterFactor randomisation of each wait, in {@code [0,1)} — without it, every replica
     *     that hit the same 429 retries in lockstep and the source is hammered in synchronised waves
     */
    public record Resilience(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double backoffMultiplier,
            double jitterFactor,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState) {

        public Resilience {
            maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
            initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
            maxBackoff = maxBackoff == null ? Duration.ofSeconds(20) : maxBackoff;
            backoffMultiplier = backoffMultiplier <= 1.0 ? 2.0 : backoffMultiplier;
            jitterFactor = jitterFactor <= 0.0 || jitterFactor >= 1.0 ? 0.5 : jitterFactor;
            slidingWindowSize = slidingWindowSize <= 0 ? 20 : slidingWindowSize;
            minimumNumberOfCalls = minimumNumberOfCalls <= 0 ? 8 : minimumNumberOfCalls;
            failureRateThreshold = failureRateThreshold <= 0 ? 50f : failureRateThreshold;
            waitDurationInOpenState =
                    waitDurationInOpenState == null ? Duration.ofSeconds(30) : waitDurationInOpenState;
            permittedCallsInHalfOpenState = permittedCallsInHalfOpenState <= 0 ? 3 : permittedCallsInHalfOpenState;
        }

        public static Resilience defaults() {
            return new Resilience(4, null, null, 2.0, 0.5, 20, 8, 50f, null, 3);
        }
    }

    /**
     * @param apiKey credential from the environment; blank means "not configured"
     * @param defaultQuery query used by scheduled incremental runs, where the user supplied none
     * @param feeds RSS/Atom feed URLs
     * @param path filesystem path for the fixture corpus
     */
    public record ConnectorSettings(
            Boolean enabled,
            String baseUrl,
            Integer requestsPerMinute,
            Integer pageSize,
            String apiKey,
            String token,
            String defaultQuery,
            List<String> feeds,
            String path) {

        public ConnectorSettings {
            feeds = feeds == null ? List.of() : List.copyOf(feeds);
        }

        public static ConnectorSettings empty() {
            return new ConnectorSettings(null, null, null, null, null, null, null, List.of(), null);
        }

        public String baseUrlOr(String fallback) {
            return blankTo(baseUrl, fallback);
        }

        public int requestsPerMinuteOr(int fallback) {
            return requestsPerMinute == null || requestsPerMinute <= 0 ? fallback : requestsPerMinute;
        }

        public int pageSizeOr(int fallback) {
            return pageSize == null || pageSize <= 0 ? fallback : pageSize;
        }

        public String defaultQueryOr(String fallback) {
            return blankTo(defaultQuery, fallback);
        }

        public boolean enabledOr(boolean fallback) {
            return enabled == null ? fallback : enabled;
        }

        /** Credential presence, not the credential itself — safe to log and to expose in the API. */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
