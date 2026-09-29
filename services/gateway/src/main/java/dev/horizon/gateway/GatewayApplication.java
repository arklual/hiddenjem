package dev.horizon.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Edge service: the only component exposed to browsers.
 *
 * <p>Responsibilities are deliberately narrow — routing, token validation, rate limiting, CORS and
 * security headers. It contains no business logic: an edge that knows about the domain becomes a
 * distributed monolith's hidden coupling point.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
