package dev.horizon.trends.adapter.analytics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.horizon.trends.application.port.TermTraceExplainer;

/**
 * Calls the engine's {@code POST /internal/explain} over HTTP.
 *
 * <p>Свой клиент и свой таймаут, отдельно от остальных вызовов движка. Повтор анализа идёт минутами,
 * а вопросы движок обрабатывает по одному, поэтому к собственному прогону прибавляется очередь из
 * чужих. Человек этого соединения не держит — он опрашивает готовность (см.
 * {@link SingleFlightTermTraceExplainer#explainIfReady}), — так что таймаут здесь страхует от
 * зависшего движка, а не от нетерпения читателя. Прежде общий таймаут в пять минут обрывал второй
 * вопрос в очереди, хотя движок ответил бы (стенд 2026-09-28).
 */
@Component
public class HttpTermTraceExplainer implements TermTraceExplainer {

    private final RestClient client;
    private final String token;

    @Autowired
    public HttpTermTraceExplainer(
            @Value("${horizon.analytics.base-url:http://analytics-service:8000}") String baseUrl,
            @Value("${horizon.analytics.explain-timeout:PT20M}") Duration timeout,
            @Value("${horizon.analytics.internal-token:}") String token) {
        this(AnalyticsClientConfiguration.client(baseUrl, timeout), token);
    }

    HttpTermTraceExplainer(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    @Override
    public Explanation explain(Request request) {
        try {
            ExplainResponse response = client.post()
                    .uri("/internal/explain")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", token)
                    .body(bodyOf(request))
                    .retrieve()
                    .body(ExplainResponse.class);
            if (response == null) {
                throw new ExplanationUnavailableException("движок вернул пустой ответ", null);
            }
            return new Explanation(
                    response.stages() == null ? List.of() : List.copyOf(response.stages()), tracesOf(response));
        } catch (RestClientException e) {
            throw new ExplanationUnavailableException("движок не ответил на запрос трассировки", e);
        }
    }

    private static List<TermTrace> tracesOf(ExplainResponse response) {
        if (response.traces() == null) {
            return List.of();
        }
        List<TermTrace> traces = new ArrayList<>(response.traces().size());
        for (TraceBody trace : response.traces()) {
            traces.add(new TermTrace(
                    trace.term(),
                    trace.canonicalTerm(),
                    trace.stage(),
                    trace.outcome(),
                    trace.reason(),
                    trace.detail() == null ? Map.of() : Map.copyOf(trace.detail()),
                    trace.inReport()));
        }
        return List.copyOf(traces);
    }

    private static Map<String, Object> bodyOf(Request request) {
        // Mirrors the AnalyzeDomain command payload field for field. It has to: the engine replays
        // the analysis from these values, and any field that differs would answer about a different
        // run while looking like an answer about this one.
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("topN", request.parameters().topN());
        parameters.put("yearsWindow", request.parameters().yearsWindow());
        parameters.put(
                "sourceClasses",
                request.parameters().sourceClasses().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList());
        parameters.put("minConfidence", request.parameters().minConfidence());
        parameters.put("includeMature", request.parameters().includeMature());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileId", request.profile().id().toString());
        profile.put("methodologyVersion", request.profile().methodologyVersion());
        profile.put("aggregator", request.profile().aggregator().name());
        profile.put("weights", request.profile().weights());
        profile.put("parameters", request.profile().parameters());
        profile.put("confidenceThreshold", request.profile().confidenceThreshold());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("researchRequestId", request.researchRequestId());
        body.put("attempt", request.attempt());
        body.put("snapshotId", request.snapshotId().toString());
        body.put("query", request.query());
        body.put("normalizedQuery", request.normalizedQuery());
        body.put("parameters", parameters);
        body.put("profile", profile);
        body.put("terms", request.terms());
        return body;
    }

    record ExplainResponse(List<String> stages, List<TraceBody> traces) {}

    record TraceBody(
            String term,
            String canonicalTerm,
            String stage,
            String outcome,
            String reason,
            Map<String, Object> detail,
            boolean inReport) {}

    /** Wiring for the engine client, kept beside its only consumer. */
    @Configuration
    @ConfigurationProperties(prefix = "horizon.analytics")
    public static class AnalyticsClientConfiguration {

        @Bean
        RestClient analyticsRestClient(
                @Value("${horizon.analytics.base-url:http://analytics-service:8000}") String baseUrl,
                @Value("${horizon.analytics.timeout:PT20S}") Duration timeout) {
            return client(baseUrl, timeout);
        }

        static RestClient client(String baseUrl, Duration timeout) {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            factory.setReadTimeout((int) timeout.toMillis());
            return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
    }
}
