package dev.horizon.trends.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.horizon.trends.config.FeatureFlag;

/**
 * Таблица «флаг → запрос»: спецификация, которой пользуются два теста.
 *
 * <p>Одна на оба контекста намеренно. {@code FeatureGateWebTest} проверяет по ней, что выключенное
 * отвечает 404, а {@code GatedEndpointsAreRoutableTest} — что при включённых флагах каждый её запрос
 * доходит до контроллера. Без второго первый зеленел бы и на неверном пути: 404 маршрутизации
 * неотличим от 404 гейта, и первая версия этой таблицы содержала ровно такие две строки.
 */
final class GatedEndpoints {

    static final UUID REPORT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID DOMAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private GatedEndpoints() {}

    static Stream<Arguments> all() {
        return Stream.of(
                Arguments.of(FeatureFlag.RADAR, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/saved-domains/digest")),
                Arguments.of(FeatureFlag.RADAR, (Supplier<MockHttpServletRequestBuilder>)
                        () -> post("/api/v1/saved-domains/" + DOMAIN + "/seen")),
                Arguments.of(FeatureFlag.SAVED_DOMAINS, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/saved-domains")),
                Arguments.of(FeatureFlag.DIRECTION_OVERLAP, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/saved-domains/overlap")),
                Arguments.of(FeatureFlag.REPORT_DELTA, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/reports/" + REPORT + "/delta")),
                Arguments.of(FeatureFlag.TERM_TRACE, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/reports/" + REPORT + "/explain?term=ии")),
                Arguments.of(FeatureFlag.REPORT_EXPORT, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/reports/" + REPORT + "/export?format=markdown")),
                Arguments.of(FeatureFlag.TOPIC_SEARCH, (Supplier<MockHttpServletRequestBuilder>)
                        () -> get("/api/v1/topics/search?q=ии")),
                Arguments.of(FeatureFlag.TREND_FEEDBACK, (Supplier<MockHttpServletRequestBuilder>)
                        () -> put("/api/v1/reports/" + REPORT + "/trends/ии/feedback")
                                .contentType("application/json")
                                .content("{\"verdict\":\"RELEVANT\"}")));
    }
}
