package dev.horizon.ingestion.connector.newswire;

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
 * GlobeNewswire на подлинных ответах: лента {@code /RssFeed/keyword/stablecoin payments} и
 * {@code robots.txt}, записанные 2026-09-28.
 *
 * <p>Сеть подменена; разбор, отбор по окну и нормализация — боевые. Живая проверка —
 * {@code MarketWiresLiveCheck}.
 */
class GlobeNewswireTest {

    private static final Instant FETCHED = Instant.parse("2026-09-28T18:20:00Z");

    private ConnectorHttpClient http;
    private GlobeNewswireConnector connector;

    @BeforeEach
    void wire() throws IOException {
        http = Mockito.mock(ConnectorHttpClient.class);
        answer("/connector/newswire/globenewswire-stablecoin-payments.xml");
        String robots = read("/connector/newswire/globenewswire-robots.txt");
        given(http.get(
                        eq(GlobeNewswireConnector.SOURCE_ID),
                        eq(URI.create("https://www.globenewswire.com/robots.txt")),
                        any(),
                        anyInt()))
                .willReturn(response("https://www.globenewswire.com/robots.txt", robots));
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new GlobeNewswireConnector(
                new ConnectorsProperties(
                        null, null, null, null, 0, null, Map.of(GlobeNewswireConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private void answer(String resource) throws IOException {
        String body = read(resource);
        given(http.get(
                        eq(GlobeNewswireConnector.SOURCE_ID),
                        argThat(uri -> uri != null && uri.getPath().startsWith("/RssFeed/keyword/")),
                        any(),
                        anyInt()))
                .willAnswer(call -> response(call.getArgument(1, URI.class).toString(), body));
    }

    private static RawHttpResponse response(String url, String body) {
        return new RawHttpResponse(GlobeNewswireConnector.SOURCE_ID, url, 200, body, "a".repeat(64), FETCHED, null);
    }

    static String read(String resource) throws IOException {
        try (InputStream stream = GlobeNewswireTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static CollectionRequest request(String query, LocalDate from, LocalDate to) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                query,
                query,
                "en",
                from,
                to,
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
    @DisplayName("пятнадцать релизов ленты: дата, адрес релиза, анонс без повтора, компания-автор")
    void readsTheKeywordFeed() {
        List<Document> documents =
                collected(request("stablecoin payments", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31)));

        assertThat(documents).hasSize(15);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.identifiers().url()).startsWith("https://www.globenewswire.com/news-release/");
            assertThat(document.venue().name()).isEqualTo("GlobeNewswire");
            assertThat(document.abstractText()).isNotBlank().doesNotContain("<pre>");
            assertThat(document.authors()).singleElement().satisfies(author -> assertThat(author.organizationName())
                    .isNotBlank());
        });
        Document coinbax = documents.stream()
                .filter(document -> document.externalRef().externalId().equals("3209148"))
                .findFirst()
                .orElseThrow();
        assertThat(coinbax.publishedOn()).isEqualTo(LocalDate.of(2025, 12, 22));
        assertThat(coinbax.title()).startsWith("Coinbax Raises $4.2M");
        assertThat(coinbax.language()).isEqualTo("en");
        assertThat(coinbax.authors()).singleElement().satisfies(author -> {
            assertThat(author.organizationName()).isEqualTo("Coinbax");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
        });
    }

    @Test
    @DisplayName("релизы вне окна отбрасываются: в 2026 году их девять из пятнадцати")
    void keepsOnlyTheWindow() {
        List<Document> documents =
                collected(request("stablecoin payments", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertThat(documents).hasSize(9);
        assertThat(documents)
                .allSatisfy(
                        document -> assertThat(document.publishedOn().getYear()).isEqualTo(2026));
    }

    @Test
    @DisplayName("фраза уходит сегментом пути, пробел кодируется, коды классификатора не спрашиваются")
    void asksEachPhraseAsAPathSegment() {
        var request = new CollectionRequest(
                UUID.randomUUID(),
                "платежи стейблкоинами",
                "платежи стейблкоинами",
                "ru",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 12, 31),
                Set.of(),
                500,
                List.of("stablecoin payments", "q-fin.GN"));

        assertThat(GlobeNewswireConnector.phrases(request)).containsExactly("stablecoin payments");
        assertThat(connector.feedUri("stablecoin payments").toString())
                .isEqualTo("https://www.globenewswire.com/RssFeed/keyword/stablecoin%20payments");
    }

    @Test
    @DisplayName("дата без секунд, как её пишет GlobeNewswire, читается")
    void readsTheDateWithoutSeconds() {
        assertThat(GlobeNewswireFeed.date("Tue, 15 Sep 2026 08:02 GMT"))
                .isEqualTo(Instant.parse("2026-09-15T08:02:00Z"));
        assertThat(PressReleaseNormalizer.language("zh-hant")).isEqualTo("zh");
    }

    @Test
    @DisplayName("ответ, который не лента, — отказ источника, а не ноль релизов")
    void aChallengePageIsAFailure() throws IOException {
        given(http.get(
                        eq(GlobeNewswireConnector.SOURCE_ID),
                        argThat(uri -> uri != null && uri.getPath().startsWith("/RssFeed/keyword/")),
                        any(),
                        anyInt()))
                .willReturn(response(
                        "https://www.globenewswire.com/RssFeed/keyword/x",
                        "<html><head><title>Just a moment...</title></head><body></body></html>"));

        assertThatThrownBy(() ->
                        collected(request("stablecoin payments", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31))))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("robots.txt, запрещающий ленту, — отказ с причиной")
    void robotsDisallowIsAFailure() {
        given(http.get(
                        eq(GlobeNewswireConnector.SOURCE_ID),
                        eq(URI.create("https://www.globenewswire.com/robots.txt")),
                        any(),
                        anyInt()))
                .willReturn(
                        response("https://www.globenewswire.com/robots.txt", "User-agent: *\nDisallow: /RssFeed/\n"));

        assertThatThrownBy(() ->
                        collected(request("stablecoin payments", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31))))
                .isInstanceOf(ConnectorException.Permanent.class)
                .hasMessageContaining("robots.txt");
    }
}
