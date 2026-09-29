package dev.horizon.ingestion.connector.alphaxiv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.RawPayloadStore;

class AlphaXivConnectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> calls = new ArrayList<>();
    private final List<String> archived = new ArrayList<>();

    private AlphaXivConnector connector(boolean enabled, String key, AlphaXivClient client) {
        var settings = new ConnectorsProperties.ConnectorSettings(
                enabled, "https://api.alphaxiv.org/mcp/v1", 30, 15, key, null, null, null, null);
        RawPayloadStore store = (source, hash, fetchedAt, payload, type) -> {
            archived.add(type + ":" + hash);
            return Optional.of("raw/" + hash);
        };
        return new AlphaXivConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of("alphaxiv", settings)),
                client,
                mapper,
                store,
                Clock.fixed(Instant.parse("2026-09-27T18:00:00Z"), ZoneOffset.UTC));
    }

    private CollectionRequest request() {
        return new CollectionRequest(
                UUID.randomUUID(),
                "робототехника",
                "робототехника",
                "ru",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 9, 27),
                Set.of(SourceClass.PREPRINT),
                100,
                List.of("robotics", "robot learning", "embodied AI"));
    }

    private static String sse(String text) {
        return "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"}]}}\n\n";
    }

    @Test
    void discoveredPaperIsBackedByOriginalText() throws Exception {
        String listing = "1. [ID=2609.24411] **Zeva-Ego: Egocentric Mid-Training** "
                + "(https://www.alphaxiv.org/abs/2609.24411). Published 2026-09-22 by Tsinghua · 10 votes: "
                + "AI GENERATED PREVIEW\n"
                + "2. [ID=2609.24411] **Duplicate** (https://www.alphaxiv.org/abs/2609.24411). "
                + "Published 2026-09-22 · 10 votes: duplicate";
        AlphaXivClient client = (tool, arguments) -> {
            calls.add(tool + ":" + arguments);
            return tool.equals("discover_papers")
                    ? sse(listing)
                    : sse("Zeva-Ego\nEgocentric human video transfers physical experience to robot actions.\n"
                            + "1. Introduction\nMore text");
        };
        var connector = connector(true, "configured-key", client);

        List<Document> documents;
        try (var stream = connector.collect(request(), null)) {
            documents = stream.documents().toList();
        }

        assertThat(documents).hasSize(1);
        assertThat(calls).hasSize(2);
        // Вопрос — словами аналитика, ключевые слова — только термины запроса: общие заполнители
        // вроде «emerging technology» инструмент сам называет вредными для качества.
        var discover = mapper.readTree(calls.get(0).split(":", 2)[1]);
        assertThat(discover.path("question").asText()).isEqualTo("робототехника");
        assertThat(discover.path("keywords")).isNotEmpty();
        assertThat(discover.path("keywords").toString())
                .doesNotContain("emerging technology", "applied research", "new methods");
        assertThat(discover.path("prioritize").asText()).isEqualTo("recency");
        assertThat(discover.has("published_before")).isFalse();
        assertThat(mapper.readTree(calls.get(1).split(":", 2)[1])
                        .path("fullText")
                        .asBoolean())
                .isTrue();
        assertThat(archived).hasSize(2);
        Document paper = documents.get(0);
        assertThat(paper.sourceClass()).isEqualTo(SourceClass.PREPRINT);
        assertThat(paper.identifiers().arxivId()).isEqualTo("2609.24411");
        assertThat(paper.publishedOn()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(paper.abstractText()).contains("Egocentric human video").doesNotContain("AI GENERATED PREVIEW");
        assertThat(paper.provenance().rawRef()).startsWith("raw/");
    }

    @Test
    void missingKeyIsUnavailableButDisabledSourceIsSwitchedOff() {
        assertThat(connector(true, "", (tool, args) -> "").descriptor().available())
                .isFalse();
        assertThat(connector(false, "", (tool, args) -> "").descriptor().switchedOff())
                .isTrue();
    }

    @Test
    void mcpToolFailureIsNotAnEmptySearch() {
        var connector = connector(
                true,
                "configured-key",
                (tool, args) ->
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,\"content\":[]}}\n\n");
        assertThatThrownBy(() -> {
                    try (var stream = connector.collect(request(), null)) {
                        stream.documents().toList();
                    }
                })
                .isInstanceOf(ConnectorException.Permanent.class);
    }
}
