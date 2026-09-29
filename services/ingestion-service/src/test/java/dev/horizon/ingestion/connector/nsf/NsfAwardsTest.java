package dev.horizon.ingestion.connector.nsf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * NSF Award Search API на подлинных ответах: «"quantum sensing"» за 2024-01-01…2026-09-28, первая
 * страница и страница со смещением 75, записанные 2026-09-28; пустой ответ — запрос несуществующей
 * фразы в тот же день.
 */
class NsfAwardsTest {

    private ConnectorHttpClient http;
    private NsfConnector connector;
    private final List<URI> requested = new ArrayList<>();

    @BeforeEach
    void wire() {
        http = Mockito.mock(ConnectorHttpClient.class);
        answer(offset -> offset == 25
                ? read("/connector/nsf/awards-quantum-sensing-offset75.json")
                : offset == 0
                        ? read("/connector/nsf/awards-quantum-sensing.json")
                        : read("/connector/nsf/awards-empty.json"));
        connector = new NsfConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of()), http, new ObjectMapper());
    }

    private interface Pages {
        String at(int offset);
    }

    private void answer(Pages pages) {
        requested.clear();
        given(http.get(
                        eq(NsfConnector.SOURCE_ID),
                        argThat(uri -> uri.getPath().endsWith("/awards.json")),
                        any(),
                        anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    requested.add(uri);
                    int offset = Integer.parseInt(uri.getRawQuery().replaceAll(".*offset=(\\d+).*", "$1"));
                    return new RawHttpResponse(
                            NsfConnector.SOURCE_ID,
                            uri.toString(),
                            200,
                            pages.at(offset),
                            "a".repeat(64),
                            Instant.parse("2026-09-28T10:00:00Z"),
                            null);
                });
    }

    private static String read(String resource) {
        try (InputStream stream = NsfAwardsTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CollectionRequest request(LocalDate from) {
        return new CollectionRequest(
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                "quantum sensing",
                "quantum sensing",
                "en",
                from,
                LocalDate.of(2026, 9, 28),
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
    @DisplayName("первая страница: фраза в кавычках, окно в формате API, дата решения, страница награды NSF")
    void readsTheFirstPage() {
        List<Document> documents = collected(request(LocalDate.of(2024, 1, 1)));

        URI first = requested.get(0);
        assertThat(first.getRawQuery())
                .contains("keyword=%22quantum%20sensing%22")
                .contains("dateStart=01/01/2024")
                .contains("dateEnd=09/28/2026")
                .contains("rpp=25")
                .contains("offset=0");
        // Вторую страницу играет записанная страница со смещением 75, третью — записанный пустой
        // ответ: короткая страница завершает формулировку.
        assertThat(requested).hasSize(3);
        assertThat(documents).hasSize(50);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.identifiers().url())
                    .isEqualTo("https://www.nsf.gov/awardsearch/show-award/?AWD_ID="
                            + document.externalRef().externalId());
            assertThat(document.publishedOn()).isBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 28));
            assertThat(document.abstractText()).isNotBlank();
        });
        Document workshop = documents.stream()
                .filter(document -> document.externalRef().externalId().equals("2631519"))
                .findFirst()
                .orElseThrow();
        assertThat(workshop.publishedOn()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(workshop.authors()).singleElement().satisfies(author -> {
            assertThat(author.organizationName()).isEqualTo("Auburn University");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.UNIVERSITY);
            assertThat(author.organizationCountry()).isEqualTo("US");
        });
    }

    @Test
    @DisplayName("получатель SBIR — компания, её программа — в издании")
    void anSbirAwardeeIsACompany() {
        Document sbir = collected(request(LocalDate.of(2024, 1, 1))).stream()
                .filter(document -> document.externalRef().externalId().equals("2507716"))
                .findFirst()
                .orElseThrow();

        assertThat(sbir.venue().name()).isEqualTo("NSF — SBIR Phase II");
        assertThat(sbir.publishedOn()).isEqualTo(LocalDate.of(2025, 8, 20));
        assertThat(sbir.authors())
                .extracting(Author::organizationName, Author::organizationType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("H-BAR INSTRUMENTS, LLC", OrganizationType.COMPANY));
    }

    @Test
    @DisplayName("окно проверяется по каждой записи: API молча игнорирует дату в неверном формате")
    void theWindowIsEnforcedLocally() {
        List<Document> documents = collected(request(LocalDate.of(2026, 8, 15)));

        assertThat(documents).isNotEmpty().allSatisfy(document -> assertThat(document.publishedOn())
                .isAfterOrEqualTo(LocalDate.of(2026, 8, 15)));
    }

    @Test
    @DisplayName("тип получателя по названию: фонд при университете — университет, неясное — без типа")
    void organizationTypesFromNames() {
        assertThat(NsfNormalizer.organizationType(
                        "University of Kansas Center for Research Inc", "Elem. Particle Physics/Theory"))
                .isEqualTo(OrganizationType.UNIVERSITY);
        assertThat(NsfNormalizer.organizationType("Stevens Institute of Technology", null))
                .isEqualTo(OrganizationType.UNIVERSITY);
        assertThat(NsfNormalizer.organizationType("Santa Fe Institute", null))
                .isEqualTo(OrganizationType.RESEARCH_INSTITUTE);
        assertThat(NsfNormalizer.organizationType("LAB2701 LLC", "SBIR Phase I"))
                .isEqualTo(OrganizationType.COMPANY);
        assertThat(NsfNormalizer.organizationType("Zymetis", null)).isNull();
    }

    @Test
    @DisplayName("totalCount: 0 — законный ноль, одна страница")
    void zeroIsZero() {
        answer(offset -> read("/connector/nsf/awards-empty.json"));

        assertThat(collected(request(LocalDate.of(2024, 1, 1)))).isEmpty();
        assertThat(requested).hasSize(1);
    }

    @Test
    @DisplayName("ответ без тела response — отказ источника, а не ноль")
    void aForeignBodyIsAFailure() {
        answer(offset -> "{\"error\":\"maintenance\"}");

        assertThatThrownBy(() -> collected(request(LocalDate.of(2024, 1, 1)))).isInstanceOf(ConnectorException.class);
    }
}
