package dev.horizon.ingestion.connector.openalex;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reads each configured account's remaining daily credits without exposing credentials. */
public class OpenAlexQuotaService {
    private static final String RATE_LIMIT_URL = "https://api.openalex.org/rate-limit";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final WebClient client;
    private final List<String> keys;
    private Snapshot cached;

    public OpenAlexQuotaService(WebClient client, OpenAlexConnector connector) {
        this(client, connector.configuredKeys());
    }

    OpenAlexQuotaService(WebClient client, List<String> keys) {
        this.client = client;
        this.keys = List.copyOf(keys);
    }

    public synchronized Snapshot snapshot() {
        if (cached != null && cached.checkedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        if (keys.isEmpty()) {
            cached = new Snapshot(0, 0, 0, 0, null, Instant.now());
            return cached;
        }
        List<AccountQuota> accounts = Flux.fromIterable(keys)
                .flatMap(this::readAccount, Math.min(keys.size(), 6))
                .collectList()
                .block(Duration.ofSeconds(8));
        if (accounts == null) {
            accounts = List.of();
        }
        long remaining = accounts.stream().mapToLong(AccountQuota::remaining).sum();
        long limit = accounts.stream().mapToLong(AccountQuota::limit).sum();
        String resetsAt = accounts.stream()
                .map(AccountQuota::resetsAt)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        cached = new Snapshot(remaining, limit, keys.size(), accounts.size(), resetsAt, Instant.now());
        return cached;
    }

    private Mono<AccountQuota> readAccount(String key) {
        return client.get()
                .uri(RATE_LIMIT_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(JsonNode.class)
                        : Mono.empty())
                .map(body -> body.path("rate_limit"))
                .filter(rate -> rate.hasNonNull("credits_remaining") && rate.hasNonNull("credits_limit"))
                .map(rate -> new AccountQuota(
                        Math.max(0, rate.path("credits_remaining").asLong()),
                        Math.max(0, rate.path("credits_limit").asLong()),
                        rate.path("resets_at").asText(null)))
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(error -> Mono.empty());
    }

    private record AccountQuota(long remaining, long limit, String resetsAt) {}

    public record Snapshot(
            long remainingCredits,
            long limitCredits,
            int keysConfigured,
            int keysChecked,
            String resetsAt,
            Instant checkedAt) {}
}
