package dev.horizon.ingestion.connector.edgar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 * Полнотекстовый поиск EDGAR на подлинных ответах (запрос «"quantum computing"», формы D и S-1,
 * окно 2025-01-01…2026-09-28, три страницы по сто файлов, записаны 2026-09-28).
 *
 * <p>Сеть подменена, разбор, отбор основных документов, окно и нормализация — боевые. Живая проверка
 * — {@code EdgarLiveCheck}.
 */
class EdgarSearchTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 28);

    private ConnectorHttpClient http;
    private EdgarConnector connector;
    private final List<URI> asked = new ArrayList<>();

    @BeforeEach
    void wire() {
        http = Mockito.mock(ConnectorHttpClient.class);
        given(http.get(eq(EdgarConnector.SOURCE_ID), any(URI.class), any(), anyInt()))
                .willAnswer(call -> {
                    URI uri = call.getArgument(1, URI.class);
                    asked.add(uri);
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    String q = params.getFirst("q");
                    String resource = q.contains("quantum")
                            ? "/connector/edgar/search-quantum-computing-from" + params.getFirst("from") + ".json"
                            : "/connector/edgar/search-empty.json";
                    return new RawHttpResponse(
                            EdgarConnector.SOURCE_ID,
                            uri.toString(),
                            200,
                            read(resource),
                            "a".repeat(64),
                            Instant.parse("2026-09-28T18:25:00Z"),
                            null);
                });
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, 600, null, null, null, null, List.of(), null);
        connector = new EdgarConnector(
                new ConnectorsProperties(
                        null, "analyst@horizon.test", null, null, 0, null, Map.of(EdgarConnector.SOURCE_ID, settings)),
                http,
                new ObjectMapper());
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = EdgarSearchTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CollectionRequest request(String query, LocalDate from, LocalDate to) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                query,
                query,
                "en",
                from,
                to,
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
    @DisplayName("три страницы: только основные документы, по одному на подачу, приложения отброшены")
    void keepsOnePrimaryDocumentPerFiling() {
        List<Document> documents = collected(request("quantum computing", FROM, TO));

        // 278 основных документов на трёх записанных страницах, из них 187 — поправки S-1/A и D/A:
        // остаются 91 первичная подача, все различны.
        assertThat(documents).hasSize(91);
        assertThat(documents.stream().map(d -> d.externalRef().externalId()).distinct())
                .hasSize(91);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.NEWS);
            assertThat(document.publishedOn()).isBetween(FROM, TO);
            assertThat(document.identifiers().url()).startsWith("https://www.sec.gov/Archives/edgar/data/");
            assertThat(document.identifiers().url()).doesNotContain("cgi-bin");
            assertThat(document.authors()).isNotEmpty().allSatisfy(author -> {
                assertThat(author.organizationType()).isEqualTo(OrganizationType.COMPANY);
                assertThat(author.organizationName()).doesNotContain("CIK");
            });
        });
        assertThat(asked).hasSize(3);
    }

    @Test
    @DisplayName("окно дат передаётся всегда: без него EFTS отвечает 500")
    void theWindowIsAlwaysSent() {
        collected(request("quantum computing", FROM, TO));

        var params = UriComponentsBuilder.fromUri(asked.get(0)).build().getQueryParams();
        assertThat(params.getFirst("dateRange")).isEqualTo("custom");
        assertThat(params.getFirst("startdt")).isEqualTo("2025-01-01");
        assertThat(params.getFirst("enddt")).isEqualTo("2026-09-28");
        assertThat(params.getFirst("forms")).isEqualTo("D,S-1");
        assertThat(asked.get(0).toString()).contains("q=%22quantum%20computing%22");
    }

    @Test
    @DisplayName("S-1: заявитель без тикера и CIK, адрес — файл в /Archives/, смысл формы в аннотации")
    void registrationStatement() {
        Document zapata = collected(request("quantum computing", FROM, TO)).stream()
                .filter(d -> d.externalRef().externalId().equals("0001683168-26-004579"))
                .findFirst()
                .orElseThrow();

        assertThat(zapata.title()).isEqualTo("S-1 — Zapata Quantum, Inc.");
        assertThat(zapata.publishedOn()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(zapata.identifiers().url())
                .isEqualTo("https://www.sec.gov/Archives/edgar/data/1843714/000168316826004579/zpat_s1.htm");
        assertThat(zapata.abstractText())
                .contains("Registration statement for a public offering")
                .contains("\"quantum computing\"")
                .contains("Boston, MA");
        assertThat(zapata.authors()).singleElement().satisfies(author -> {
            assertThat(author.organizationName()).isEqualTo("Zapata Quantum, Inc.");
            assertThat(author.organizationCountry()).isEqualTo("US");
        });
    }

    @Test
    @DisplayName("Form D: раунд по Regulation D, адрес — официальное HTML-представление анкеты")
    void formD() {
        Document spv = collected(request("quantum computing", FROM, TO)).stream()
                .filter(d -> d.externalRef().externalId().equals("0002077212-25-000001"))
                .findFirst()
                .orElseThrow();

        assertThat(spv.title()).isEqualTo("Form D — Quantum Computing SPV LLC");
        assertThat(spv.publishedOn()).isEqualTo(LocalDate.of(2025, 7, 21));
        assertThat(spv.identifiers().url())
                .isEqualTo(
                        "https://www.sec.gov/Archives/edgar/data/2077212/000207721225000001/xslFormDX01/primary_doc.xml");
        assertThat(spv.abstractText()).contains("Regulation D (a private funding round)");
    }

    @Test
    @DisplayName("подачи вне окна запроса не попадают в выдачу, даже если сервер их вернул")
    void windowIsCheckedLocally() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        List<Document> documents = collected(request("quantum computing", from, TO));

        assertThat(documents).isNotEmpty().allSatisfy(d -> assertThat(d.publishedOn())
                .isAfterOrEqualTo(from));
        assertThat(documents.size()).isLessThan(278);
    }

    @Test
    @DisplayName("пустой ответ — законный ноль, без ошибки")
    void zeroIsNotAFailure() {
        assertThat(collected(request("zzqqxx notaphrase", FROM, TO))).isEmpty();
        assertThat(asked).hasSize(1);
    }

    @Test
    @DisplayName("отказ сервиса роняет прогон — отказ не выдаётся за ноль")
    void failureIsNotZero() {
        given(http.get(eq(EdgarConnector.SOURCE_ID), any(URI.class), any(), anyInt()))
                .willThrow(new ConnectorException.Retryable(EdgarConnector.SOURCE_ID, 500, "Internal server error"));

        assertThatThrownBy(() -> collected(request("quantum computing", FROM, TO)))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @DisplayName("русская формулировка без латиницы не спрашивается, если есть цели словаря; коды — никогда")
    void onlyPhrasesAreAsked() {
        var request = new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777778"),
                "квантовые вычисления",
                "квантовые вычисления",
                "ru",
                FROM,
                TO,
                Set.of(),
                5000,
                List.of("zzqqxx notaphrase", "cs.ET"));
        collected(request);

        assertThat(asked).singleElement().satisfies(uri -> assertThat(uri.toString())
                .doesNotContain("cs.ET"));
    }

    @Test
    @DisplayName("выключенный источник не участвует в сборе")
    void switchedOff() {
        var off = new EdgarConnector(
                new ConnectorsProperties(
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        Map.of(
                                EdgarConnector.SOURCE_ID,
                                new ConnectorsProperties.ConnectorSettings(
                                        false, null, null, null, null, null, null, List.of(), null))),
                http,
                new ObjectMapper());
        assertThat(off.supports(request("quantum computing", FROM, TO))).isFalse();
        assertThat(off.descriptor().switchedOff()).isTrue();
        verify(http, never()).get(any(), any(), any(), anyInt());
    }
}
