package dev.horizon.ingestion.connector.producthunt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Product Hunt на подлинных лентах, записанных 2026-09-28: общая {@code /feed}, рубрика
 * {@code ?category=fintech} и несуществующая рубрика {@code ?category=edge-ai}, на которую площадка
 * молча отдала общую ленту.
 */
class ProductHuntFeedTest {

    private static final Instant FETCHED = Instant.parse("2026-09-28T18:20:00Z");

    private ConnectorHttpClient http;
    private ProductHuntConnector connector;

    @BeforeEach
    void wire() throws IOException {
        http = Mockito.mock(ConnectorHttpClient.class);
        String main = read("/connector/producthunt/feed.xml");
        String fintech = read("/connector/producthunt/feed-category-fintech.xml");
        String unknown = read("/connector/producthunt/feed-category-edge-ai-unknown.xml");
        String robots = read("/connector/producthunt/robots.txt");
        given(http.get(eq(ProductHuntConnector.SOURCE_ID), argThat(ProductHuntFeedTest::isFeed), any(), anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    String query = uri.getRawQuery();
                    String body = query == null ? main : query.equals("category=fintech") ? fintech : unknown;
                    return response(uri.toString(), body);
                });
        given(http.get(
                        eq(ProductHuntConnector.SOURCE_ID),
                        eq(URI.create("https://www.producthunt.com/robots.txt")),
                        any(),
                        anyInt()))
                .willReturn(response("https://www.producthunt.com/robots.txt", robots));
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new ProductHuntConnector(
                new ConnectorsProperties(
                        null, null, null, null, 0, null, Map.of(ProductHuntConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private static boolean isFeed(URI uri) {
        return uri != null && uri.getPath().equals("/feed");
    }

    private static RawHttpResponse response(String url, String body) {
        return new RawHttpResponse(ProductHuntConnector.SOURCE_ID, url, 200, body, "a".repeat(64), FETCHED, null);
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = ProductHuntFeedTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CollectionRequest request(String query, LocalDate from) {
        return new CollectionRequest(
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                query,
                query,
                "en",
                from,
                LocalDate.of(2026, 12, 31),
                Set.of(),
                500,
                List.of());
    }

    private List<Document> collected(CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    @Test
    @DisplayName("общая лента фильтруется по словам: из пятидесяти запусков «coding agent» — два")
    void filtersTheMainFeedByWords() {
        List<Document> documents = collected(request("coding agent", LocalDate.of(2020, 1, 1)));

        assertThat(documents).extracting(Document::title).containsExactlyInAnyOrder("vantage.ai", "GenCode");
        Document vantage = documents.stream()
                .filter(document -> document.title().equals("vantage.ai"))
                .findFirst()
                .orElseThrow();
        assertThat(vantage.sourceClass()).isEqualTo(SourceClass.NEWS);
        assertThat(vantage.abstractText()).isEqualTo("See and control what your coding agent does.");
        assertThat(vantage.publishedOn()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(vantage.identifiers().url()).isEqualTo("https://www.producthunt.com/products/vantage-ai-2");
        assertThat(vantage.authors()).singleElement().satisfies(author -> {
            assertThat(author.organizationName()).isEqualTo("vantage.ai");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
        });
    }

    @Test
    @DisplayName("настоящая рубрика берётся целиком: «fintech» — пятьдесят запусков с марта 2026")
    void aRealCategoryIsTakenWhole() {
        List<Document> documents = collected(request("fintech", LocalDate.of(2020, 1, 1)));

        assertThat(documents).hasSize(50);
        assertThat(documents).extracting(Document::title).contains("Subscrr");
        assertThat(documents)
                .allSatisfy(document -> assertThat(document.publishedOn()).isAfterOrEqualTo(LocalDate.of(2026, 3, 21)));
    }

    @Test
    @DisplayName("несуществующая рубрика — это общая лента, и её запуски без совпадения слов не берутся")
    void anUnknownCategoryFallsBackToTheMainFeed() {
        List<Document> documents = collected(request("edge AI", LocalDate.of(2020, 1, 1)));

        assertThat(documents).isEmpty();
    }

    @Test
    @DisplayName("окно отсекает и запуски рубрики")
    void theWindowApplies() {
        List<Document> documents = collected(request("fintech", LocalDate.of(2026, 9, 1)));

        assertThat(documents).hasSize(3);
        assertThat(documents)
                .allSatisfy(document -> assertThat(document.publishedOn()).isAfterOrEqualTo(LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("общая лента, которая не лента, — отказ источника, а не ноль запусков")
    void aChallengePageIsAFailure() {
        given(http.get(eq(ProductHuntConnector.SOURCE_ID), argThat(ProductHuntFeedTest::isFeed), any(), anyInt()))
                .willReturn(response(
                        "https://www.producthunt.com/feed",
                        "<!DOCTYPE html><html><head><title>Just a moment...</title></head></html>"));

        assertThatThrownBy(() -> collected(request("coding agent", LocalDate.of(2020, 1, 1))))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("слова сравниваются без окончания множественного числа, рубрика пишется через дефис")
    void wordsAndSlugs() {
        assertThat(ProductHuntConnector.words("AI agents for Payments"))
                .containsExactly("ai", "agent", "for", "payment");
        assertThat(ProductHuntConnector.slug("Stablecoin payments")).isEqualTo("stablecoin-payments");
        assertThat(ProductHuntConnector.slug("edge AI")).isEqualTo("edge-ai");
    }
}
