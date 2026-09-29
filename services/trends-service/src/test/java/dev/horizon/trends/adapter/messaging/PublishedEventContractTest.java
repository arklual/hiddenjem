package dev.horizon.trends.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.event.TrendReportGenerated;
import dev.horizon.trends.support.Fixtures;

/**
 * What we publish must match what we published a schema for.
 *
 * <p>{@code contracts/schemas/} is what another team builds a consumer against — a BI feed, a
 * notification service, a training job. Until now nothing kept the producers honest against it, and
 * that kind of drift is invisible from inside: our own consumer changes in the same commit as the
 * producer, so only an outside team ever notices, after they have shipped.
 *
 * <p>The check is structural rather than a full JSON Schema validation: the validator available
 * offline speaks draft-04 while these schemas declare 2020-12, and a validator that disagrees with
 * the dialect would give confident wrong answers. Required-field presence and the absence of
 * undeclared fields is what a consumer actually breaks on.
 */
class PublishedEventContractTest {

    private static final Path SCHEMAS =
            Path.of(System.getProperty("user.dir")).getParent().getParent().resolve("contracts/schemas");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static Stream<org.junit.jupiter.params.provider.Arguments> publishedEvents() {
        var request = Fixtures.assemblingRequest();
        var report = Fixtures.report(request, 3);
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "trend-report-generated.event.json", (DomainEvent) new TrendReportGenerated(
                                UUID.randomUUID(),
                                Instant.parse("2026-03-01T10:00:00Z"),
                                report.id(),
                                request.id(),
                                Fixtures.requester(),
                                request.query().normalized(),
                                3,
                                false,
                                "em-1.0.0",
                                Fixtures.SNAPSHOT_ID)),
                org.junit.jupiter.params.provider.Arguments.of(
                        "trend-feedback-recorded.event.json", (DomainEvent) TrendFeedback.record(
                                Fixtures.USER_ID,
                                report.id(),
                                report.trends().getFirst().trendKey(),
                                TrendFeedback.Verdict.RELEVANT,
                                "полезно",
                                Instant.parse("2026-03-01T10:00:00Z"))));
    }

    private static JsonNode schema(String name) throws IOException {
        return MAPPER.readTree(Files.readString(SCHEMAS.resolve(name)));
    }

    private static List<String> namesOf(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedEvents")
    void everyRequiredFieldOfTheSchemaIsPresentInThePayload(String schemaName, DomainEvent event) throws IOException {
        JsonNode payload = MAPPER.valueToTree(event.payload());
        JsonNode required = schema(schemaName).path("required");

        var missing = new ArrayList<String>();
        required.forEach(field -> {
            String name = field.asText();
            if (!payload.hasNonNull(name)) {
                missing.add(name);
            }
        });

        assertThat(missing)
                .as("обязательные по схеме поля отсутствуют в публикуемом событии")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedEvents")
    void thePayloadCarriesNoFieldTheSchemaDoesNotDeclare(String schemaName, DomainEvent event) throws IOException {
        // An undeclared field is not harmless: a consumer generated from the schema either drops it
        // — and the producer believes it is being read — or refuses the message outright when the
        // schema forbids extras.
        JsonNode payload = MAPPER.valueToTree(event.payload());
        var declared = namesOf(schema(schemaName).path("properties"));

        assertThat(namesOf(payload))
                .as("поля, которых нет в опубликованной схеме")
                .isSubsetOf(declared);
    }

    @Test
    void schemasExistForTheEventsThisServicePublishes() throws IOException {
        // A published event with no schema is the same drift seen from the other side: the consumer
        // team has nothing to build against and reverse-engineers the payload from a sample.
        for (var arguments : publishedEvents().toList()) {
            String name = (String) arguments.get()[0];
            assertThat(SCHEMAS.resolve(name)).as("схема события").exists();
            assertThat(schema(name).path("required").isArray()).isTrue();
        }
    }
}
