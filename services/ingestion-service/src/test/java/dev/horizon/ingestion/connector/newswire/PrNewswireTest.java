package dev.horizon.ingestion.connector.newswire;

import static dev.horizon.ingestion.connector.newswire.GlobeNewswireTest.read;
import static dev.horizon.ingestion.connector.newswire.GlobeNewswireTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

/**
 * PR Newswire на подлинных страницах поиска, записанных 2026-09-28: «neuromorphic computing»
 * (21 результат, одна страница), «stablecoin payments» (первая из многих страниц) и запрос,
 * на который площадка ответила «No Results Found».
 */
class PrNewswireTest {

    private static final Instant FETCHED = Instant.parse("2026-09-28T18:20:00Z");

    private ConnectorHttpClient http;
    private PrNewswireConnector connector;

    @BeforeEach
    void wire() throws IOException {
        http = Mockito.mock(ConnectorHttpClient.class);
        String robots = read("/connector/newswire/prnewswire-robots.txt");
        given(http.get(
                        eq(PrNewswireConnector.SOURCE_ID),
                        eq(URI.create("https://www.prnewswire.com/robots.txt")),
                        any(),
                        anyInt()))
                .willReturn(response("https://www.prnewswire.com/robots.txt", robots));
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new PrNewswireConnector(
                new ConnectorsProperties(
                        null, null, null, null, 0, null, Map.of(PrNewswireConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private void answer(int page, String resource) throws IOException {
        String body = read(resource);
        given(http.get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, page)), any(), anyInt()))
                .willAnswer(call -> response(call.getArgument(1, URI.class).toString(), body));
    }

    private static boolean isSearch(URI uri, int page) {
        return uri != null
                && uri.getPath().equals("/search/news/")
                && uri.getRawQuery().contains("page=" + page + "&");
    }

    private static RawHttpResponse response(String url, String body) {
        return new RawHttpResponse(PrNewswireConnector.SOURCE_ID, url, 200, body, "a".repeat(64), FETCHED, null);
    }

    private List<Document> collected(String query, LocalDate from) {
        try (var stream = connector.collect(request(query, from, LocalDate.of(2026, 12, 31)), null)) {
            return stream.documents().toList();
        }
    }

    @Test
    @DisplayName("21 карточка: дата по Нью-Йорку, адрес релиза, фрагмент, выпустившая организация")
    void readsTheResultCards() throws IOException {
        answer(1, "/connector/newswire/prnewswire-neuromorphic-computing.html");

        List<Document> documents = collected("neuromorphic computing", LocalDate.of(2020, 1, 1));

        assertThat(documents).hasSize(21);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.identifiers().url()).startsWith("https://www.prnewswire.com/news-releases/");
            assertThat(document.abstractText()).isNotBlank().doesNotContain("<em>");
            assertThat(document.venue().name()).isEqualTo("PR Newswire");
        });
        Document first = documents.get(0);
        assertThat(first.externalRef().externalId()).isEqualTo("302890432");
        assertThat(first.publishedOn()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(first.title())
                .isEqualTo(
                        "Dongguk University Researchers Develop Battery-Free Flexible Device for Neuromorphic Sensing");
        assertThat(first.authors()).singleElement().satisfies(author -> {
            assertThat(author.organizationName()).isEqualTo("Dongguk University");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.UNIVERSITY);
        });
        assertThat(documents)
                .flatExtracting(Document::authors)
                .filteredOn(author -> "Synopsys, Inc.".equals(author.organizationName()))
                .allSatisfy(author -> assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY))
                .isNotEmpty();
        // Неполная страница — последняя: вторую не спрашиваем.
        verify(http, times(1))
                .get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, 1)), any(), anyInt());
    }

    @Test
    @DisplayName("полная страница ведёт на следующую, неполная останавливает обход")
    void pagesUntilAShortPage() throws IOException {
        answer(1, "/connector/newswire/prnewswire-stablecoin-payments-page1.html");
        answer(2, "/connector/newswire/prnewswire-neuromorphic-computing.html");

        List<Document> documents = collected("stablecoin payments", LocalDate.of(2020, 1, 1));

        assertThat(documents).hasSize(25 + 21);
        verify(http, times(1))
                .get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, 2)), any(), anyInt());
        verify(http, times(0))
                .get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, 3)), any(), anyInt());
    }

    @Test
    @DisplayName("страница, дошедшая до релизов старше окна, — последняя: выдача идёт от новых к старым")
    void stopsOnceResultsLeaveTheWindow() throws IOException {
        answer(1, "/connector/newswire/prnewswire-stablecoin-payments-page1.html");
        answer(2, "/connector/newswire/prnewswire-neuromorphic-computing.html");

        List<Document> documents = collected("stablecoin payments", LocalDate.of(2026, 8, 1));

        assertThat(documents).hasSize(20);
        assertThat(documents)
                .allSatisfy(document -> assertThat(document.publishedOn()).isAfterOrEqualTo(LocalDate.of(2026, 8, 1)));
        verify(http, times(0))
                .get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, 2)), any(), anyInt());
    }

    @Test
    @DisplayName("«No Results Found» — законный ноль")
    void noResultsIsAZero() throws IOException {
        answer(1, "/connector/newswire/prnewswire-no-results.html");

        assertThat(collected("qzxvkjwplm horizonnothing", LocalDate.of(2020, 1, 1)))
                .isEmpty();
    }

    @Test
    @DisplayName("страница без карточек и без пометки «ничего не найдено» — отказ, а не ноль")
    void aChallengePageIsAFailure() {
        given(http.get(eq(PrNewswireConnector.SOURCE_ID), argThat(uri -> isSearch(uri, 1)), any(), anyInt()))
                .willReturn(response(
                        "https://www.prnewswire.com/search/news/",
                        "<!DOCTYPE html><html><head><title>Access Denied</title></head><body></body></html>"));

        assertThatThrownBy(() -> collected("stablecoin payments", LocalDate.of(2020, 1, 1)))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("дата карточки читается так, как напечатана")
    void readsThePrintedDate() {
        assertThat(PrNewswireResults.date("Dec 04, 2025, 08:44 ET")).isEqualTo(LocalDate.of(2025, 12, 4));
        assertThat(PrNewswireResults.date("Sep 8, 2026, 09:00 ET")).isEqualTo(LocalDate.of(2026, 9, 8));
    }
}
