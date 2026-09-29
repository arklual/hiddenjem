package dev.horizon.gateway.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import dev.horizon.gateway.filter.SecurityHeadersFilter;

/**
 * Край платформы без входа: CORS и заголовки безопасности.
 *
 * <p>Вход выведен из продукта — организация одна, и API открыт так же, как интерфейс. Шлюз
 * больше не проверяет токены и не делит запросы на «вошедших» и «анонимов»; осталось то, что
 * защищает браузер, а не пользователя: список разрешённых источников и заголовки против встраивания
 * и подмены содержимого. Оба фильтра — обычные веб-фильтры, раньше их вешала цепочка Spring
 * Security, которой больше нет.
 */
@Configuration
public class EdgeConfiguration {

    private final GatewayProperties properties;

    public EdgeConfiguration(GatewayProperties properties) {
        this.properties = properties;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter(properties.contentSecurityPolicy());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public CorsWebFilter corsWebFilter() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key", "Last-Event-ID", "traceparent"));
        configuration.setExposedHeaders(List.of("Location", "Retry-After", "traceparent"));
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
