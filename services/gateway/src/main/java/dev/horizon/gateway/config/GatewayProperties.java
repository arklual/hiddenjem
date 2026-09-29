package dev.horizon.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Edge configuration. Everything here is environment-specific and must never be hard-coded. */
@ConfigurationProperties(prefix = "horizon.gateway")
public record GatewayProperties(
        List<String> allowedOrigins,
        String contentSecurityPolicy,
        int requestsPerMinute,
        int burstCapacity) {

    public GatewayProperties {
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("http://localhost:3000", "http://localhost:5173")
                : List.copyOf(allowedOrigins);
        contentSecurityPolicy = contentSecurityPolicy == null || contentSecurityPolicy.isBlank()
                ? "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"
                : contentSecurityPolicy;
        // Входа нет, и лимит один — на адрес клиента. Значение прежнего лимита вошедшего аналитика:
        // интерфейс при открытии радара делает десятки запросов, и прежние тридцать в минуту для
        // анонима отвечали бы ему 429.
        requestsPerMinute = requestsPerMinute <= 0 ? 300 : requestsPerMinute;
        burstCapacity = burstCapacity <= 0 ? 60 : burstCapacity;
    }
}
