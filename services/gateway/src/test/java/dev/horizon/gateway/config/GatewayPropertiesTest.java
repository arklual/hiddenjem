package dev.horizon.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayPropertiesTest {

    @Test
    @DisplayName("falls back to safe defaults instead of an empty CORS allowlist")
    void appliesDefaults() {
        var properties = new GatewayProperties(null, null, 0, 0);

        assertThat(properties.allowedOrigins()).isNotEmpty();
        assertThat(properties.contentSecurityPolicy()).contains("frame-ancestors 'none'");
        // Лимит один — на адрес клиента, и не меньше прежнего лимита вошедшего аналитика.
        assertThat(properties.requestsPerMinute()).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("keeps an explicitly configured allowlist verbatim")
    void keepsExplicitOrigins() {
        var origins = List.of("https://horizon.example.bank");

        var properties = new GatewayProperties(origins, null, 100, 20);

        assertThat(properties.allowedOrigins()).containsExactlyElementsOf(origins);
    }
}
