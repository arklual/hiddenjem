package dev.horizon.ingestion.connector.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Поисковые ленты отраслевых изданий на подлинном ответе EdgeIR (запрос «edge ai», 2026-09-26).
 *
 * <p>Главное здесь — различение нуля и отказа: издание, ответившее страницей проверки на робота,
 * выбывает, а если не ответило ни одно — источник обязан упасть, а не принести ноль.
 */
class IndustryMediaTest {

    private static final String EDGEIR = "https://www.edgeir.com/search/{q}/feed/rss2/?paged={page}";
    private static final String CLOSED = "https://closed.example/search/{q}/feed/rss2/?paged={page}";

    private ConnectorHttpClient http;
    private String feed;

    @BeforeEach
    void wire() throws IOException {
        http = Mockito.mock(ConnectorHttpClient.class);
        try (InputStream stream =
                IndustryMediaTest.class.getResourceAsStream("/connector/industry/edgeir-search-rss.xml")) {
            assertThat(stream).isNotNull();
            feed = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // robots.txt у всех — «разрешено всё».
        given(http.get(anyString(), argThat(uri -> uri.getPath().equals("/robots.txt")), any(), anyInt()))
                .willAnswer(call -> response(call.getArgument(1, URI.class), "User-agent: *\nDisallow: /wp-admin/\n"));
    }

    private static RawHttpResponse response(URI uri, String body) {
        return new RawHttpResponse(
                "industry:" + uri.getHost(),
                uri.toString(),
                200,
                body,
                "d".repeat(64),
                Instant.parse("2026-09-26T18:13:00Z"),
                null);
    }

    private IndustryMediaConnector connector(List<String> feeds) {
        var settings = new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, feeds, null);
        return new IndustryMediaConnector(
                new ConnectorsProperties(
                        null, null, null, null, 0, null, Map.of(IndustryMediaConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private static CollectionRequest request(String phrase) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                phrase,
                phrase,
                "en",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 12, 31),
                Set.of(),
                500,
                List.of());
    }

    private static List<Document> collected(IndustryMediaConnector connector, CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    private void edgeirAnswers() {
        given(http.get(
                        eq("industry:www.edgeir.com"),
                        argThat(uri -> uri.getPath().startsWith("/search/")),
                        any(),
                        anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    // Вторая страница у WordPress за концом выдачи — 404.
                    if (uri.getQuery().contains("paged=2")) {
                        throw new ConnectorException.Permanent("industry:www.edgeir.com", 404, "not found");
                    }
                    return response(uri, feed);
                });
    }

    @Test
    @DisplayName("статья — новость издания; организация — само издание, одно на все запросы")
    void anArticleBelongsToItsOutlet() {
        edgeirAnswers();

        List<Document> documents = collected(connector(List.of(EDGEIR)), request("edge ai"));

        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.language()).isEqualTo("en");
            assertThat(document.authors())
                    .allSatisfy(author -> assertThat(author.organizationName()).isEqualTo("edgeir.com"));
            assertThat(document.topics()).isEmpty();
        });
    }

    @Test
    @DisplayName("запись без всех слов формулировки не берётся: поиск WordPress ищет слова порознь")
    void everyWordOfThePhraseMustBePresent() {
        edgeirAnswers();

        // Та же лента в ответ на запрос, слов которого в ней нет: поиск издания ошибся, отбор — нет.
        List<Document> documents = collected(connector(List.of(EDGEIR)), request("quantum annealing"));

        assertThat(documents).isEmpty();
    }

    @Test
    @DisplayName("отказавшее издание выбывает, остальные собираются")
    void oneFailedOutletDoesNotCostTheOthers() {
        edgeirAnswers();
        given(http.get(
                        eq("industry:closed.example"),
                        argThat(uri -> uri.getPath().startsWith("/search/")),
                        any(),
                        anyInt()))
                .willThrow(new ConnectorException.Permanent("industry:closed.example", 403, "Just a moment..."));

        List<Document> documents = collected(connector(List.of(CLOSED, EDGEIR)), request("edge ai"));

        assertThat(documents).isNotEmpty();
    }

    @Test
    @DisplayName("не ответило ни одно издание — отказ источника, а не ноль статей")
    void everyOutletFailingIsAFailure() {
        given(http.get(
                        eq("industry:closed.example"),
                        argThat(uri -> uri.getPath().startsWith("/search/")),
                        any(),
                        anyInt()))
                .willThrow(new ConnectorException.Permanent("industry:closed.example", 403, "Just a moment..."));

        assertThatThrownBy(() -> collected(connector(List.of(CLOSED)), request("edge ai")))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("Ни одно отраслевое издание не ответило");
    }

    @Test
    @DisplayName("формулировка подставляется в путь, страница — в параметр")
    void theTemplateIsFilledIn() {
        URI uri = IndustryMediaConnector.uriOf(EDGEIR, "Mobile Edge Computing", 2);

        assertThat(uri.toString())
                .isEqualTo("https://www.edgeir.com/search/mobile%20edge%20computing/feed/rss2/?paged=2");
    }
}
