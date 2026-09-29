package dev.horizon.ingestion.connector.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

class OpenAlexQuotaServiceTest {
    @Test
    void sumsAvailableAccountsAndDoesNotExposeKeys() {
        List<String> seen = new CopyOnWriteArrayList<>();
        var client = WebClient.builder()
                .exchangeFunction(request -> {
                    String key = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
                    seen.add(key);
                    if ("Bearer unavailable".equals(key)) {
                        return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
                    }
                    String remaining = "Bearer first".equals(key) ? "120" : "80";
                    String body = """
                            {"rate_limit":{"credits_remaining":%s,"credits_limit":1000,
                            "resets_at":"2026-09-30T00:00:00Z"}}
                            """.formatted(remaining);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(body)
                            .build());
                })
                .build();
        var service = new OpenAlexQuotaService(client, List.of("first", "second", "unavailable"));

        var result = service.snapshot();

        assertThat(result.remainingCredits()).isEqualTo(200);
        assertThat(result.limitCredits()).isEqualTo(2000);
        assertThat(result.keysConfigured()).isEqualTo(3);
        assertThat(result.keysChecked()).isEqualTo(2);
        assertThat(result.resetsAt()).isEqualTo("2026-09-30T00:00:00Z");
        assertThat(seen).containsExactlyInAnyOrder("Bearer first", "Bearer second", "Bearer unavailable");
        assertThat(service.snapshot()).isSameAs(result);
        assertThat(seen).hasSize(3);
        assertThat(result.toString()).doesNotContain("first", "second", "unavailable");
    }
}
