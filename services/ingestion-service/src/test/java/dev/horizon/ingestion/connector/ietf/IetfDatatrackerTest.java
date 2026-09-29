package dev.horizon.ingestion.connector.ietf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * IETF Datatracker на подлинных ответах (фраза «post-quantum cryptography», черновики, изменённые с
 * 2025-01-01; записаны 2026-09-28).
 *
 * <p>Записаны пять ответов: поиск по названию, поиск по аннотации и по одному пакетному ответу
 * первых ревизий, авторов и персон — для всех 34 черновиков обоих поисков сразу. Коннектор
 * спрашивает то же самое частями, по странице; лишние записи в пакетном ответе он не использует.
 */
class IetfDatatrackerTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 28);

    private ConnectorHttpClient http;
    private IetfConnector connector;
    private final List<URI> asked = new ArrayList<>();

    @BeforeEach
    void wire() {
        http = Mockito.mock(ConnectorHttpClient.class);
        given(http.get(eq(IetfConnector.SOURCE_ID), any(URI.class), any(), anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    asked.add(uri);
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    String path = uri.getPath();
                    String resource;
                    if (path.endsWith("/doc/document/")) {
                        boolean known =
                                String.valueOf(params.toSingleValueMap()).contains("post-quantum%20cryptography");
                        resource = !known
                                ? "/connector/ietf/drafts-empty.json"
                                : params.containsKey("title__icontains")
                                        ? "/connector/ietf/drafts-title.json"
                                        : "/connector/ietf/drafts-abstract.json";
                    } else if (path.endsWith("/doc/newrevisiondocevent/")) {
                        resource = "/connector/ietf/revisions.json";
                    } else if (path.endsWith("/doc/documentauthor/")) {
                        resource = "/connector/ietf/authors.json";
                    } else if (path.endsWith("/person/person/")) {
                        resource = "/connector/ietf/persons.json";
                    } else {
                        throw new AssertionError("неожиданный запрос " + uri);
                    }
                    return new RawHttpResponse(
                            IetfConnector.SOURCE_ID,
                            uri.toString(),
                            200,
                            read(resource),
                            "a".repeat(64),
                            Instant.parse("2026-09-28T18:27:00Z"),
                            null);
                });
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new IetfConnector(
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(IetfConnector.SOURCE_ID, settings)),
                http,
                new ObjectMapper());
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = IetfDatatrackerTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CollectionRequest request(String query) {
        return new CollectionRequest(
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                query,
                query,
                "en",
                FROM,
                TO,
                Set.of(),
                5000,
                List.of());
    }

    private List<Document> collected(CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    @Test
    @DisplayName("название и аннотация без повторов; в окне — только черновики, начатые в окне")
    void titleAndAbstractWithinWindow() {
        List<Document> documents = collected(request("post-quantum cryptography"));

        // 34 черновика изменялись с 2025-01-01; девять из них начаты раньше — это не сигнал окна.
        assertThat(documents).hasSize(25);
        assertThat(documents.stream().map(d -> d.externalRef().externalId()).distinct())
                .hasSize(25);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.STANDARD);
            assertThat(document.publishedOn()).isBetween(FROM, TO);
            assertThat(document.identifiers().url()).startsWith("https://datatracker.ietf.org/doc/draft-");
            assertThat(document.abstractText()).isNotBlank();
        });
        assertThat(documents)
                .extracting(d -> d.externalRef().externalId())
                .doesNotContain("draft-ietf-tls-hybrid-design", "draft-ietf-pquip-pqc-engineers");
    }

    @Test
    @DisplayName("дата — первая ревизия, а не последнее изменение записи")
    void dateIsTheFirstRevision() {
        Document hsm = collected(request("post-quantum cryptography")).stream()
                .filter(d -> d.externalRef().externalId().equals("draft-reddy-pquip-pqc-hsm"))
                .findFirst()
                .orElseThrow();

        assertThat(hsm.publishedOn()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(hsm.identifiers().url()).isEqualTo("https://datatracker.ietf.org/doc/draft-reddy-pquip-pqc-hsm/");
        assertThat(hsm.venue().name()).isEqualTo("IETF, индивидуальный черновик");
    }

    @Test
    @DisplayName("авторы с аффилиациями: компания — компания, университет — университет")
    void authorsCarryOrganizations() {
        List<Document> documents = collected(request("post-quantum cryptography"));

        Document constrained = documents.stream()
                .filter(d -> d.externalRef().externalId().equals("draft-ietf-pquip-pqc-hsm-constrained"))
                .findFirst()
                .orElseThrow();
        assertThat(constrained.venue().name()).isEqualTo("IETF, черновик рабочей группы pquip");
        assertThat(constrained.authors()).isNotEmpty();
        assertThat(constrained.authors()).anySatisfy(author -> {
            assertThat(author.organizationName()).isEqualTo("Nokia");
            assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
        });

        assertThat(documents.stream().flatMap(d -> d.authors().stream()))
                .filteredOn(author -> "VIT-AP University".equals(author.organizationName()))
                .isNotEmpty()
                .allSatisfy(author -> assertThat(author.organizationType()).isEqualTo(OrganizationType.UNIVERSITY));
    }

    @Test
    @DisplayName("фраза спрашивается по названию и по аннотации, окно — через time__gte, по одному пакету на страницу")
    void asksBothFieldsWithTheWindow() {
        collected(request("post-quantum cryptography"));

        List<URI> searches = asked.stream()
                .filter(uri -> uri.getPath().endsWith("/doc/document/"))
                .toList();
        assertThat(searches).hasSize(2);
        assertThat(searches.get(0).toString()).contains("title__icontains=post-quantum%20cryptography");
        assertThat(searches.get(1).toString()).contains("abstract__icontains=post-quantum%20cryptography");
        assertThat(searches).allSatisfy(uri -> assertThat(uri.toString()).contains("time__gte=2025-01-01"));
        // Две страницы поиска, по три пакетных запроса к каждой.
        assertThat(asked).hasSize(8);
    }

    @Test
    @DisplayName("пустой список — законный ноль: дополнительных запросов нет")
    void zeroIsNotAFailure() {
        assertThat(collected(request("zzqqxx notaphrase"))).isEmpty();
        assertThat(asked).hasSize(2);
    }

    @Test
    @DisplayName("отказ сервиса роняет прогон — отказ не выдаётся за ноль")
    void failureIsNotZero() {
        given(http.get(eq(IetfConnector.SOURCE_ID), any(URI.class), any(), anyInt()))
                .willThrow(new ConnectorException.Retryable(IetfConnector.SOURCE_ID, 503, "Service unavailable"));

        assertThatThrownBy(() -> collected(request("post-quantum cryptography")))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("типы организаций по аффилиации")
    void organizationTypes() {
        assertThat(IetfNormalizer.typeOf("Cisco Systems")).isEqualTo(OrganizationType.COMPANY);
        assertThat(IetfNormalizer.typeOf("University of Waterloo")).isEqualTo(OrganizationType.UNIVERSITY);
        assertThat(IetfNormalizer.typeOf("BSI")).isEqualTo(OrganizationType.GOVERNMENT);
        assertThat(IetfNormalizer.typeOf("UK National Cyber Security Centre")).isEqualTo(OrganizationType.GOVERNMENT);
        assertThat(IetfNormalizer.organizationOf("Independent")).isNull();
        assertThat(IetfNormalizer.countryOf("United Kingdom")).isEqualTo("GB");
        assertThat(IetfNormalizer.countryOf("USA")).isEqualTo("US");
    }
}
