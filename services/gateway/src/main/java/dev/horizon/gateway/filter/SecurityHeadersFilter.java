package dev.horizon.gateway.filter;

import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Applies OWASP-recommended response headers at the single point every response passes through
 * (NFR-S5).
 *
 * <p>Setting them here rather than in each service guarantees they cannot be forgotten when a new
 * service is added, and keeps the policy in one reviewable place.
 */
public class SecurityHeadersFilter implements WebFilter {

    private final String contentSecurityPolicy;

    public SecurityHeadersFilter(String contentSecurityPolicy) {
        this.contentSecurityPolicy = contentSecurityPolicy;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            // Configured but previously unsent: `GatewayProperties.contentSecurityPolicy` had a
            // sensible default and a unit test, and nothing ever wrote the header. A policy that
            // exists only in configuration is worse than none — it reads as a control in review.
            headers.set("Content-Security-Policy", contentSecurityPolicy);
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            headers.set("Cross-Origin-Opener-Policy", "same-origin");
            // HSTS is only meaningful over TLS; behind a TLS-terminating ingress it is still correct
            // to advertise it, and harmless in plain-HTTP local development.
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.remove("Server");
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
