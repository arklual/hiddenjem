package dev.horizon.ingestion.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;
import dev.horizon.platform.common.event.DomainEvent;

/**
 * The corpus events must match the schemas published for them.
 *
 * <p>These two are the ones an outside consumer is most likely to want — a document feed and a
 * "corpus is ready" signal — and the ones nothing had ever checked. Drift here is invisible from
 * inside the project: our own consumer is changed in the same commit as the producer, so only a team
 * building against {@code contracts/schemas/} would notice, and only after they had shipped.
 *
 * <p>Structural rather than full JSON Schema validation: the validator available offline speaks
 * draft-04 while these schemas declare 2020-12, and a validator that disagrees with the dialect
 * gives confident wrong answers. Required-field presence and undeclared fields are what a generated
 * consumer actually breaks on.
 */
class PublishedEventContractTest {

    private static final Path SCHEMAS =
            Path.of(System.getProperty("user.dir")).getParent().getParent().resolve("contracts/schemas");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final Instant AT = Instant.parse("2026-03-01T10:00:00Z");

    private static Document document() {
        return Document.builder()
                .id(UUID.fromString("019fd789-0000-7000-8000-000000000001"))
                .externalRef("arxiv", "2401.00001")
                .sourceClass(SourceClass.PREPRINT)
                .title("Speculative decoding for large language models")
                .abstractText("Мы предлагаем метод ускорения авторегрессионного вывода.")
                .language("en")
                .publishedOn(LocalDate.of(2024, 1, 15))
                .doi("10.1000/example")
                .arxivId("2401.00001")
                .url("https://arxiv.org/abs/2401.00001")
                .provenance(new Provenance(
                        "arxiv", AT, "https://export.arxiv.org/api/query?id=2401.00001", 200, "a".repeat(64), null))
                .build();
    }

    private static CorpusSnapshot snapshot() {
        return CorpusSnapshot.assemble(
                UUID.fromString("019fd789-0000-7000-8000-000000000002"),
                "artificial intelligence",
                LocalDate.of(2018, 1, 1),
                LocalDate.of(2025, 12, 31),
                List.of(document().id()),
                List.of("arxiv", "openalex"),
                List.of("uspto"));
    }

    private static Stream<Arguments> publishedEvents() {
        return Stream.of(
                Arguments.of("document-ingested.event.json", (DomainEvent) DocumentIngested.of(document(), AT)),
                Arguments.of("corpus-collected.event.json", (DomainEvent) CorpusCollected.of(
                        UUID.fromString("019fd789-0000-7000-8000-000000000003"), 1, snapshot(), AT)));
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

        var missing = new ArrayList<String>();
        schema(schemaName).path("required").forEach(field -> {
            if (!payload.hasNonNull(field.asText())) {
                missing.add(field.asText());
            }
        });

        assertThat(missing)
                .as("обязательные по схеме поля отсутствуют в публикуемом событии")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedEvents")
    void thePayloadCarriesNoFieldTheSchemaDoesNotDeclare(String schemaName, DomainEvent event) throws IOException {
        // An undeclared field is not harmless: a generated consumer either drops it — and the
        // producer believes it is being read — or refuses the message when the schema forbids extras.
        JsonNode payload = MAPPER.valueToTree(event.payload());

        assertThat(namesOf(payload))
                .as("поля, которых нет в опубликованной схеме")
                .isSubsetOf(namesOf(schema(schemaName).path("properties")));
    }
}
