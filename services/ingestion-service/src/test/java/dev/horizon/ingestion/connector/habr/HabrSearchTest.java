package dev.horizon.ingestion.connector.habr;

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
 * Поиск Хабра на подлинном ответе (запрос «speculative decoding», 2026-09-26).
 *
 * <p>Сеть подменена, разбор, отбор по окну и нормализация — боевые. Живая проверка того же
 * коннектора — {@code ProductSourcesLiveCheck}.
 */
class HabrSearchTest {

    private ConnectorHttpClient http;
    private HabrConnector connector;

    @BeforeEach
    void wire() throws IOException {
        http = Mockito.mock(ConnectorHttpClient.class);
        String feed = read("/connector/habr/search-rss.xml");
        given(http.get(
                        eq(HabrConnector.SOURCE_ID),
                        argThat(uri -> uri.getPath().endsWith("/rss/search/")),
                        any(),
                        anyInt()))
                .willAnswer(call -> new RawHttpResponse(
                        HabrConnector.SOURCE_ID,
                        call.getArgument(1, URI.class).toString(),
                        200,
                        feed,
                        "a".repeat(64),
                        Instant.parse("2026-09-26T18:13:00Z"),
                        null));
        // robots.txt Хабра в той части, что касается нас: HTML-поиск закрыт, лента — нет.
        given(http.get(eq(HabrConnector.SOURCE_ID), eq(URI.create("https://habr.com/robots.txt")), any(), anyInt()))
                .willReturn(new RawHttpResponse(
                        HabrConnector.SOURCE_ID,
                        "https://habr.com/robots.txt",
                        200,
                        "User-agent: *\nCrawl-delay: 10\nDisallow: /search/\nDisallow: /ru/search/\n",
                        "b".repeat(64),
                        Instant.parse("2026-09-26T18:13:00Z"),
                        null));
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new HabrConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(HabrConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = HabrSearchTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CollectionRequest request(String query, List<String> targets) {
        return new CollectionRequest(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                query,
                query,
                "en",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 12, 31),
                Set.of(),
                500,
                targets);
    }

    private List<Document> collected(CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    @Test
    @DisplayName("двадцать публикаций, русские, класс — новость, без меток кампаний в адресе")
    void readsTheSearchFeed() {
        List<Document> documents = collected(request("speculative decoding", List.of()));

        assertThat(documents).hasSize(20);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.language()).isEqualTo("ru");
            assertThat(document.identifiers().url())
                    .startsWith("https://habr.com/")
                    .doesNotContain("utm_");
            assertThat(document.abstractText()).doesNotContain("Читать далее");
        });
    }

    @Test
    @DisplayName("блог компании даёт организацию-компанию, личная публикация — нет")
    void aCompanyBlogNamesTheCompany() {
        List<Document> documents = collected(request("speculative decoding", List.of()));

        Document corporate = documents.stream()
                .filter(document -> document.identifiers().url().contains("/companies/yandex/"))
                .findFirst()
                .orElseThrow();
        assertThat(corporate.authors()).allSatisfy(author -> {
            assertThat(author.organizationName()).isEqualTo("yandex");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
        });
        Document personal = documents.stream()
                .filter(document -> !document.identifiers().url().contains("/companies/"))
                .findFirst()
                .orElseThrow();
        assertThat(personal.authors())
                .allSatisfy(author -> assertThat(author.organizationName()).isNull());
    }

    @Test
    @DisplayName("русская формулировка аналитика спрашивается сама, коды классификатора — нет")
    void theRussianWordingIsAskedAsIs() {
        var request = request("периферийные вычисления", List.of("edge computing", "cs.DC"));

        assertThat(HabrConnector.phrases(request)).containsExactly("периферийные вычисления", "edge computing");
    }

    @Test
    @DisplayName("лента, которая не лента, — отказ источника, а не ноль публикаций")
    void aChallengePageIsAFailureNotAnEmptyResult() {
        given(http.get(
                        eq(HabrConnector.SOURCE_ID),
                        argThat(uri -> uri.getPath().endsWith("/rss/search/")),
                        any(),
                        anyInt()))
                .willReturn(new RawHttpResponse(
                        HabrConnector.SOURCE_ID,
                        "https://habr.com/ru/rss/search/",
                        200,
                        "<!DOCTYPE html><html><head><title>Just a moment...</title></head></html>",
                        "c".repeat(64),
                        Instant.parse("2026-09-26T18:13:00Z"),
                        null));

        assertThatThrownBy(() -> collected(request("speculative decoding", List.of())))
                .isInstanceOf(ConnectorException.class);
    }
}
