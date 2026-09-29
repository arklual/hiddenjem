package dev.horizon.ingestion.connector.uspto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Patent Public Search на ответах, записанных с площадки 28.09.2026: сессия, две страницы выдачи по
 * «neuromorphic computing» (121 семейство), честный ноль и ошибка разбора запроса BRS.
 */
class UsptoSearchTest {

    private static final LocalDate FROM = LocalDate.of(2020, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 28);
    private static final Map<String, String> TOKEN = Map.of("x-access-token", "recorded-token");

    private final List<String> calls = new ArrayList<>();

    private UsptoConnector connector(Function<String, PpubsTransport.Reply> search) {
        return connector(search, body -> new PpubsTransport.Reply(200, read("session.json"), TOKEN));
    }

    private UsptoConnector connector(
            Function<String, PpubsTransport.Reply> search, Function<String, PpubsTransport.Reply> session) {
        PpubsTransport transport = (method, uri, headers, body) -> {
            calls.add(method + " " + uri.getPath());
            if (uri.getPath().equals("/robots.txt")) {
                return new PpubsTransport.Reply(404, "", Map.of());
            }
            if (uri.getPath().endsWith("/users/me/session")) {
                return session.apply(body);
            }
            return search.apply(body);
        };
        var settings = new ConnectorsProperties.ConnectorSettings(true, null, 6, null, null, null, null, List.of(), null);
        return new UsptoConnector(
                new ConnectorsProperties(
                        null, "analyst@horizon.test", null, null, 0, null, Map.of(UsptoConnector.SOURCE_ID, settings)),
                new ObjectMapper(),
                new NoopRawPayloadStore(),
                Clock.fixed(Instant.parse("2026-09-28T22:45:00Z"), ZoneOffset.UTC),
                transport,
                duration -> {},
                Duration.ZERO);
    }

    private static PpubsTransport.Reply ok(String resource) {
        return new PpubsTransport.Reply(200, read(resource), Map.of());
    }

    private static String read(String resource) {
        try (InputStream stream = UsptoSearchTest.class.getResourceAsStream("/connector/uspto/" + resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CollectionRequest request(String query) {
        return new CollectionRequest(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                query,
                query,
                "en",
                FROM,
                TO,
                Set.of(),
                5000,
                List.of());
    }

    private static List<Document> collected(UsptoConnector connector, CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    private static PpubsTransport.Reply pages(String body) {
        return ok(body.contains("\"start\":0,") ? "search-neuromorphic-page0.json" : "search-neuromorphic-page1.json");
    }

    @Test
    @DisplayName("две страницы: по документу на семейство, патентный класс, дата в окне, ссылка на ppubs")
    void twoPagesOneDocumentPerFamily() {
        var connector = connector(UsptoSearchTest::pages);

        List<Document> documents = collected(connector, request("neuromorphic computing"));

        // 100 семейств на двух страницах по 50; заявка и выданный по ней патент — одно семейство.
        assertThat(documents).isNotEmpty().hasSizeLessThanOrEqualTo(100);
        assertThat(documents.stream().map(d -> d.externalRef().externalId()).distinct()).hasSize(documents.size());
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.sourceClass()).isEqualTo(SourceClass.PATENT);
            assertThat(document.publishedOn()).isBetween(FROM, TO);
            assertThat(document.identifiers().url()).startsWith(UsptoNormalizer.PUBLIC_URL);
            assertThat(document.title()).doesNotContain("<span");
        });
        assertThat(calls.stream().filter(c -> c.endsWith("/session"))).as("одна сессия на прогон").hasSize(1);
        assertThat(calls.stream().filter(c -> c.endsWith("/searchWithBeFamily")))
                .as("не глубже двух страниц на формулировку")
                .hasSize(UsptoConnector.MAX_PAGES_PER_PHRASE);
    }

    @Test
    @DisplayName("пустая выдача — законный ноль, а не отказ")
    void emptyResultIsZero() {
        var connector = connector(body -> ok("search-empty.json"));

        assertThat(collected(connector, request("neuromorphic computing"))).isEmpty();
    }

    @Test
    @DisplayName("ошибка разбора запроса приходит с кодом 200 — и всё равно отказ")
    void queryErrorIsFailure() {
        var connector = connector(body -> ok("search-query-error.json"));

        assertThatThrownBy(() -> collected(connector, request("neuromorphic computing")))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("Unmatched parentheses");
    }

    @Test
    @DisplayName("истёкшая сессия заменяется один раз, и тот же запрос повторяется")
    void expiredSessionIsRenewedOnce() {
        int[] searches = {0};
        var connector = connector(body -> ++searches[0] == 1
                ? new PpubsTransport.Reply(401, "unauthorized", Map.of())
                : ok("search-empty.json"));

        assertThat(collected(connector, request("neuromorphic computing"))).isEmpty();
        assertThat(calls.stream().filter(c -> c.endsWith("/session"))).hasSize(2);
    }

    @Test
    @DisplayName("ограничение частоты — временный отказ после трёх попыток, а не ноль")
    void throttlingIsRetryableFailure() {
        var connector = connector(body -> new PpubsTransport.Reply(429, "Too many requests", Map.of()));

        assertThatThrownBy(() -> collected(connector, request("neuromorphic computing")))
                .isInstanceOf(ConnectorException.class);
        assertThat(calls.stream().filter(c -> c.endsWith("/searchWithBeFamily"))).hasSize(3);
    }

    @Test
    @DisplayName("отказ в анонимной сессии называет причину — вход под учётной записью USPTO")
    void refusedAnonymousSessionSaysWhy() {
        var connector = connector(
                UsptoSearchTest::pages, body -> new PpubsTransport.Reply(401, "sign in required", Map.of()));

        assertThatThrownBy(() -> collected(connector, request("neuromorphic computing")))
                .isInstanceOf(ConnectorException.Permanent.class)
                .hasMessageContaining("учётной записью USPTO");
    }

    @Test
    @DisplayName("русская формулировка не тратит запрос: индекс USPTO английский")
    void cyrillicPhrasesAreSkipped() {
        assertThat(UsptoConnector.phrases(request("нейроморфные вычисления"))).isEmpty();
        assertThat(UsptoConnector.phrases(request("neuromorphic computing"))).containsExactly("neuromorphic computing");
    }

    @Test
    @DisplayName("запрос BRS: фраза в названии или реферате, дата публикации в окне")
    void queryRestrictsTitleAbstractAndWindow() {
        assertThat(UsptoConnector.query("neuromorphic computing", FROM, TO))
                .isEqualTo("(\"neuromorphic computing\").ti,ab. AND @PD>=20200101<=20260928");
        assertThat(URI.create(UsptoNormalizer.PUBLIC_URL + "20260282756.pn.").getHost())
                .isEqualTo("ppubs.uspto.gov");
    }
}
