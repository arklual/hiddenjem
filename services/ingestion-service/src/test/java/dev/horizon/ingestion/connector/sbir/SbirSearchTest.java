package dev.horizon.ingestion.connector.sbir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * SBIR.gov на подлинных страницах: выдача «quantum sensing» за 2024–2026 годы и десять страниц
 * наград из неё, записанные 2026-09-28.
 *
 * <p>Сеть подменена, разбор, отбор по окну и нормализация — боевые. Живая проверка —
 * {@code GrantSourcesLiveCheck}.
 */
class SbirSearchTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-28T10:00:00Z");

    private ConnectorHttpClient http;
    private SbirConnector connector;
    private final List<URI> listings = new ArrayList<>();

    @BeforeEach
    void wire() {
        http = Mockito.mock(ConnectorHttpClient.class);
        answerListing(read("/connector/sbir/search-quantum-sensing.html"));
        given(http.get(
                        eq(SbirConnector.SOURCE_ID),
                        argThat(uri -> uri.getPath().matches("/awards/\\d+")),
                        any(),
                        anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    String id = uri.getPath().substring("/awards/".length());
                    return response(uri, read("/connector/sbir/award-" + id + ".html"));
                });
        given(http.get(eq(SbirConnector.SOURCE_ID), eq(URI.create("https://www.sbir.gov/robots.txt")), any(), anyInt()))
                .willAnswer(call -> response(call.getArgument(1, URI.class), read("/connector/sbir/robots.txt")));
        connector = connector(10);
    }

    private SbirConnector connector(int awardsPerPhrase) {
        var settings = new ConnectorsProperties.ConnectorSettings(
                true, null, 600, awardsPerPhrase, null, null, null, List.of(), null);
        return new SbirConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(SbirConnector.SOURCE_ID, settings)),
                http,
                new CrawlDelay(Duration.ZERO));
    }

    private void answerListing(String html) {
        given(http.get(eq(SbirConnector.SOURCE_ID), argThat(uri -> uri.getPath().equals("/awards")), any(), anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    listings.add(uri);
                    return response(uri, html);
                });
    }

    private static RawHttpResponse response(URI uri, String body) {
        return new RawHttpResponse(
                SbirConnector.SOURCE_ID, uri.toString(), 200, body, "a".repeat(64), FETCHED_AT, null);
    }

    private static String read(String resource) {
        try (InputStream stream = SbirSearchTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CollectionRequest request(LocalDate from, LocalDate to) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                "quantum sensing",
                "quantum sensing",
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

    private static Document byId(List<Document> documents, String awardId) {
        return documents.stream()
                .filter(document -> document.externalRef().externalId().equals(awardId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("десять наград выдачи: получатель — компания, адрес — страница награды, класс — новость")
    void readsTheListingAndEveryAward() {
        List<Document> documents = collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28)));

        assertThat(documents).hasSize(10);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.language()).isEqualTo("en");
            assertThat(document.identifiers().url())
                    .isEqualTo("https://www.sbir.gov/awards/"
                            + document.externalRef().externalId());
            assertThat(document.abstractText()).isNotBlank();
            Author awardee = document.authors().get(0);
            assertThat(awardee.organizationType()).isEqualTo(OrganizationType.COMPANY);
            assertThat(awardee.organizationCountry()).isEqualTo("US");
        });
        assertThat(listings).hasSize(1);
        assertThat(listings.get(0).getRawQuery())
                .contains("keywords=quantum%20sensing")
                .contains("year%5B2024%5D=2024")
                .contains("year%5B2026%5D=2026");
    }

    @Test
    @DisplayName("STTR: дата начала работ, фаза и заказчик в издании, университет-партнёр вторым участником")
    void anSttrAwardNamesItsResearchPartner() {
        Document sttr = byId(collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28))), "218952");

        assertThat(sttr.title()).startsWith("STTR Phase I: Beyond Shot Noise");
        assertThat(sttr.publishedOn()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(sttr.venue().name()).isEqualTo("SBIR.gov — STTR Phase I, NSF");
        assertThat(sttr.authors())
                .extracting(Author::organizationName)
                .containsExactly("LUMINOVA BIOTECH LLC", "Massachusetts Institute of Technology");
        assertThat(sttr.authors().get(1).organizationType()).isEqualTo(OrganizationType.UNIVERSITY);
    }

    @Test
    @DisplayName("награда без даты начала датируется годом награды, ветвь заказчика не теряется")
    void anAwardWithoutStartDateFallsBackToItsAwardYear() {
        List<Document> documents = collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28)));

        assertThat(byId(documents, "215229").publishedOn()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(byId(documents, "216466").venue().name()).isEqualTo("SBIR.gov — STTR Phase I, DOW / USAF");
        assertThat(byId(documents, "217382").publishedOn()).isEqualTo(LocalDate.of(2024, 11, 14));
    }

    @Test
    @DisplayName("окно проверяется по точной дате награды, а не только по году выдачи")
    void theWindowIsCheckedAgainstTheExactDate() {
        List<Document> documents = collected(request(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 9, 28)));

        assertThat(documents).extracting(Document::publishedOn).allSatisfy(date -> assertThat(date)
                .isAfterOrEqualTo(LocalDate.of(2025, 6, 1)));
        assertThat(documents)
                .extracting(document -> document.externalRef().externalId())
                .doesNotContain("217382", "215229", "216813")
                .contains("218952", "215084");
    }

    @Test
    @DisplayName("предел наград на формулировку ограничивает число запросов к страницам наград")
    void theAwardCapBoundsRequests() {
        connector = connector(3);

        List<Document> documents = collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28)));

        assertThat(documents).hasSize(3);
        verify(http, never())
                .get(
                        eq(SbirConnector.SOURCE_ID),
                        eq(URI.create("https://www.sbir.gov/awards/216000")),
                        any(),
                        anyInt());
    }

    @Test
    @DisplayName("«No results found.» — законный ноль")
    void anEmptyListingIsZero() {
        answerListing(read("/connector/sbir/search-empty.html"));

        assertThat(collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28))))
                .isEmpty();
    }

    @Test
    @DisplayName("страница, которая не выдача, — отказ источника, а не ноль наград")
    void aChallengePageIsAFailure() {
        answerListing("<!DOCTYPE html><html><head><title>Just a moment...</title></head></html>");

        assertThatThrownBy(() -> collected(request(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28))))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("коды классификатора в цели не уходят, длинная формулировка укорачивается до поля формы")
    void phrasesFitTheSearchForm() {
        var request = new CollectionRequest(
                UUID.randomUUID(),
                "квантовые сенсоры",
                "квантовые сенсоры",
                "ru",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 9, 28),
                Set.of(),
                500,
                List.of("quantum sensing", "cs.ET", "nitrogen vacancy center magnetometry for biomedical imaging"));

        assertThat(SbirConnector.phrases(request))
                .containsExactly("quantum sensing", "nitrogen vacancy center magnetometry for");
    }
}
