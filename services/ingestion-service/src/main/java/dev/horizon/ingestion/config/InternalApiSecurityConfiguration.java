package dev.horizon.ingestion.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Внутренняя поверхность {@code /internal/**}: общий секрет в заголовке.
 *
 * <p>Это не вход пользователя — входа в продукте нет вовсе, — а граница между службами: корпус
 * читает аналитический движок, и открыть {@code /internal/**} наружу значило бы отдать корпус
 * целиком любому, кто дотянется до порта. Тот же приём, которым защищён {@code /internal/analyze}
 * у самого движка: один механизм на обе стороны одной границы.
 *
 * <p>Пустой секрет запрещает доступ, а не открывает его: незаданная переменная — обычное состояние
 * свежего развёртывания, и умолчание «пускать всех» открыло бы корпус ровно там, где о защите ещё
 * не думали.
 *
 * <p>Обычный фильтр сервлета, а не цепочка Spring Security: пользовательской безопасности больше
 * нет, и тянуть фреймворк ради одного заголовка незачем.
 */
@Configuration
public class InternalApiSecurityConfiguration {

    /** Заголовок тот же, что у внутренних эндпоинтов движка, — граница одна, механизм один. */
    public static final String HEADER = "X-Internal-Token";

    @Bean
    public FilterRegistrationBean<SharedSecretFilter> internalApiSharedSecret(
            @Value("${horizon.internal.token:}") String token) {
        var registration = new FilterRegistrationBean<>(new SharedSecretFilter(token));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static final class SharedSecretFilter extends OncePerRequestFilter {

        private final String expected;

        SharedSecretFilter(String expected) {
            this.expected = expected == null ? "" : expected.trim();
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (expected.isEmpty()) {
                // Не 401, а 404: отключённая поверхность не должна сообщать, что она существует.
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            String provided = request.getHeader(HEADER);
            if (provided == null || !MessageDigest.isEqual(
                    provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
