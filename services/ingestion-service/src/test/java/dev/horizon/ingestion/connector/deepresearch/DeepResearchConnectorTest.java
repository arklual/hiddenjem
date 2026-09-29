package dev.horizon.ingestion.connector.deepresearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.config.DeepResearchProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.AnalysisMode;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.RawPayloadStore;

/**
 * Разбор ответа глубокого исследования на записанном живом прогоне («периферийные вычисления»,
 * Luna, 2026-09-27; три документа из тринадцати).
 *
 * <p>Главное здесь — что именно становится документом: страница, которую агент прочитал, с её
 * текстом, датой и происхождением, и ничего от модели. И второе — отказ источника ({@code status:
 * failed}) обязан уронить источник, а не принести ноль.
 */
class DeepResearchConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String recorded;
    private final List<String> requests = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private final List<String> archived = new ArrayList<>();

    @BeforeEach
    void load() throws IOException {
        try (InputStream stream =
                getClass().getResourceAsStream("/connector/deepresearch/edge-response.json")) {
            assertThat(stream).isNotNull();
            recorded = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private DeepResearchConnector connector(boolean enabled, String nlpUrl, DeepResearchClient client) {
        var settings = new ConnectorsProperties.ConnectorSettings(enabled, null, null, null, null, null, null, null, null);
        RawPayloadStore store = (source, hash, fetchedAt, payload, type) -> {
            archived.add(source + "/" + hash);
            return Optional.of("raw/" + source + "/" + hash + ".json");
        };
        return new DeepResearchConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(DeepResearchConnector.SOURCE_ID, settings)),
                new DeepResearchProperties(nlpUrl, Duration.ofMinutes(6), 40, 30, 20, null),
                client,
                objectMapper,
                store,
                Clock.fixed(Instant.parse("2026-09-27T06:33:00Z"), ZoneOffset.UTC));
    }

    private DeepResearchClient answering(String body) {
        return (request, timeout) -> {
            requests.add(request);
            timeouts.add(timeout);
            return body;
        };
    }

    private static CollectionRequest request(Set<SourceClass> classes) {
        return request(classes, AnalysisMode.FAST);
    }

    private static CollectionRequest request(Set<SourceClass> classes, AnalysisMode mode) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                "периферийные вычисления",
                "периферийные вычисления",
                "ru",
                LocalDate.of(2021, 1, 1),
                LocalDate.of(2026, 9, 27),
                classes,
                5000,
                List.of("edge computing", "mobile edge computing"),
                mode);
    }

    private static List<Document> collected(DeepResearchConnector connector, CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    @Test
    @DisplayName("выключен по умолчанию — это решение оператора, а не пробел в покрытии")
    void switchedOffByDefault() {
        var connector = connector(false, "http://nlp", answering(recorded));

        assertThat(connector.descriptor().switchedOff()).isTrue();
        assertThat(connector.supports(request(Set.of()))).isFalse();
    }

    @Test
    @DisplayName("включён без адреса сервиса моделей — недоступен, и отчёт об этом скажет")
    void unavailableWithoutNlpUrl() {
        var connector = connector(true, "", answering(recorded));

        assertThat(connector.descriptor().available()).isFalse();
        assertThat(connector.descriptor().switchedOff()).isFalse();
    }

    @Test
    @DisplayName("документ — прочитанная страница: её текст, дата, класс по месту находки и след в архиве")
    void aDocumentIsTheFetchedPage() throws IOException {
        var connector = connector(true, "http://nlp", answering(recorded));

        List<Document> documents = collected(connector, request(Set.of()));

        assertThat(documents).hasSize(3);
        assertThat(requests).hasSize(1);
        JsonNode sent = objectMapper.readTree(requests.get(0));
        assertThat(sent.path("query").asText()).isEqualTo("периферийные вычисления");
        assertThat(sent.path("targets")).hasSize(2);
        assertThat(sent.path("windowFrom").asText()).isEqualTo("2021-01-01");
        assertThat(sent.path("timeBudgetSeconds").asLong()).isEqualTo(330);
        assertThat(sent.path("maxIterations").asInt()).isEqualTo(40);
        assertThat(sent.path("maxFetches").asInt()).isEqualTo(30);
        assertThat(timeouts).containsExactly(Duration.ofMinutes(7));
        assertThat(archived).hasSize(1);

        Document paper = documents.stream().filter(d -> d.sourceClass() == SourceClass.PREPRINT).findFirst().orElseThrow();
        assertThat(paper.identifiers().arxivId()).isEqualTo("2405.21009");
        assertThat(paper.publishedOn()).isEqualTo(LocalDate.of(2024, 5, 31));
        assertThat(paper.abstractText()).containsIgnoringCase("WebAssembly");
        assertThat(paper.provenance().requestUrl()).isEqualTo("https://arxiv.org/abs/2405.21009");
        assertThat(paper.provenance().rawRef()).startsWith("raw/deepresearch/");

        Document news = documents.stream().filter(d -> d.sourceClass() == SourceClass.NEWS).findFirst().orElseThrow();
        assertThat(news.authors()).allSatisfy(author -> {
            assertThat(author.organizationName()).isEqualTo("edgeir.com");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
        });

        Document repository =
                documents.stream().filter(d -> d.sourceClass() == SourceClass.CODE_REPOSITORY).findFirst().orElseThrow();
        assertThat(repository.authors()).extracting(a -> a.organizationName()).containsOnly("wasmerio");

        assertThat(documents).allSatisfy(document -> {
            assertThat(document.externalRef().sourceId()).isEqualTo("deepresearch");
            assertThat(document.topics()).as("имена модели не становятся рубриками").isEmpty();
            assertThat(document.abstractText()).doesNotContain("not evidence");
        });
    }

    @Test
    @DisplayName("качественный анализ просит у сервиса моделей бюджет качества и ждёт ответа дольше")
    void qualityModeAsksForTheQualityBudget() throws IOException {
        var connector = connector(true, "http://nlp", answering(recorded));

        collected(connector, request(Set.of(), AnalysisMode.QUALITY));

        JsonNode sent = objectMapper.readTree(requests.get(0));
        // Двадцать минут агенту: бюджет PT20M30S без полуминуты на последний ход.
        assertThat(sent.path("timeBudgetSeconds").asLong()).isEqualTo(1200);
        assertThat(sent.path("maxIterations").asInt()).isEqualTo(120);
        assertThat(sent.path("maxFetches").asInt()).isEqualTo(90);
        assertThat(sent.path("minSources").asInt()).isEqualTo(40);
        // Ожидание — по бюджету этого запроса: ожидание быстрого режима оборвало бы качественный
        // на седьмой минуте.
        assertThat(timeouts).containsExactly(Duration.ofMinutes(21).plusSeconds(30));
    }

    @Test
    @DisplayName("бюджет качества настраивается отдельно, а незаданное берётся из его умолчаний")
    void qualityBudgetIsConfigurable() {
        var properties = new DeepResearchProperties(
                "http://nlp",
                null,
                0,
                0,
                0,
                new DeepResearchProperties.Budget(Duration.ofMinutes(15), 0, 60, 0));

        assertThat(properties.budget(AnalysisMode.FAST))
                .isEqualTo(new DeepResearchProperties.Budget(Duration.ofMinutes(6), 40, 30, 25));
        assertThat(properties.budget(AnalysisMode.QUALITY))
                .isEqualTo(new DeepResearchProperties.Budget(Duration.ofMinutes(15), 120, 60, 40));
    }

    @Test
    @DisplayName("запрошенные классы соблюдаются: без новостей — без страниц отраслевых изданий")
    void requestedClassesAreRespected() {
        var connector = connector(true, "http://nlp", answering(recorded));

        List<Document> documents = collected(connector, request(Set.of(SourceClass.PREPRINT)));

        assertThat(documents).extracting(Document::sourceClass).containsOnly(SourceClass.PREPRINT);
    }

    @Test
    @DisplayName("отказ источников — исключение, а не ноль документов")
    void aFailedResearchIsAFailure() {
        String failed = "{\"status\":\"failed\",\"model\":\"gpt-5.6-luna\",\"documents\":[],\"technologies\":[],"
                + "\"stats\":{\"searches\":4,\"searchesFailed\":4,\"pagesFetched\":0,\"pagesFailed\":0},"
                + "\"trace\":[],\"report\":\"\"}";
        var connector = connector(true, "http://nlp", answering(failed));

        assertThatThrownBy(() -> collected(connector, request(Set.of())))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("поисков 4, отказов 4");
    }

    @Test
    @DisplayName("честный ноль — источник ответил, подтверждённых имён нет")
    void anHonestZero() {
        String zero = "{\"status\":\"ok\",\"model\":\"gpt-5.6-luna\",\"documents\":[],\"technologies\":[],"
                + "\"stats\":{\"searches\":6,\"searchesOk\":6,\"pagesFetched\":5},\"trace\":[],\"report\":\"\"}";
        var connector = connector(true, "http://nlp", answering(zero));

        assertThat(collected(connector, request(Set.of()))).isEmpty();
    }
}
