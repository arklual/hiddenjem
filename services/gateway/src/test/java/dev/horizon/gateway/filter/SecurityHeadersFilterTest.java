package dev.horizon.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SecurityHeadersFilterTest {

    private static final String CSP = "default-src 'self'; frame-ancestors 'none'";

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter(CSP);

    @Test
    @DisplayName("adds the OWASP baseline headers to every response")
    void addsSecurityHeaders() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reports/1").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())
                        .then(exchange.getResponse().setComplete()))
                .verifyComplete();

        var headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("Strict-Transport-Security")).contains("max-age=31536000");
        assertThat(headers.getFirst("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        // Regression guard: the policy used to be configured, tested for its default value, and
        // never actually written to a response.
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo(CSP);
    }

    @Test
    @DisplayName("never leaks the Server header")
    void removesServerHeader() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
        exchange.getResponse().getHeaders().set("Server", "netty");

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())
                        .then(exchange.getResponse().setComplete()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("Server")).isNull();
    }
}
