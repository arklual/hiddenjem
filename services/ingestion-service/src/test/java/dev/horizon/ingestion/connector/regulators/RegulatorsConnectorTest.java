package dev.horizon.ingestion.connector.regulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
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
 * Площадки регуляторов на подлинных ответах, записанных 28.09.2026 с агентом HorizonBot: перечень
 * проектов BIS (четыре страницы) и страницы Agorá, Mandala и Dunbar, таблица песочницы FCA, четыре
 * ленты Банка России и {@code robots.txt} всех трёх.
 *
 * <p>Главное — различение нуля и отказа: запрос не по теме даёт ноль без ошибки, площадка-заглушка
 * выбывает, а если не ответила ни одна — источник падает.
 */
class RegulatorsConnectorTest {

    private static final Instant FETCHED = Instant.parse("2026-09-28T18:20:00Z");
    private static final String BIS = "https://www.bis.org/about/innovation-hub/projects";
    private static final String FCA = "https://www.fca.org.uk/firms/innovation/regulatory-sandbox/accepted-firms";

    private ConnectorHttpClient http;
    /** Адрес → записанный ответ; адрес вне карты отвечает 404, как отвечал бы сайт. */
    private Map<String, String> pages;

    @BeforeEach
    void wire() {
        http = Mockito.mock(ConnectorHttpClient.class);
        pages = new HashMap<>();
        pages.put("https://www.bis.org/robots.txt", fixture("bis-robots.txt"));
        pages.put("https://www.fca.org.uk/robots.txt", fixture("fca-robots.txt"));
        pages.put("https://www.cbr.ru/robots.txt", fixture("cbr-robots.txt"));
        pages.put(BIS, fixture("bis-projects-page0.html"));
        pages.put(BIS + "?page=1", fixture("bis-projects-page1.html"));
        pages.put(BIS + "?page=2", fixture("bis-projects-page2.html"));
        pages.put(BIS + "?page=3", fixture("bis-projects-page3.html"));
        pages.put("https://www.bis.org/project/agora", fixture("bis-agora.html"));
        pages.put("https://www.bis.org/project/mandala", fixture("bis-mandala.html"));
        pages.put("https://www.bis.org/project/dunbar", fixture("bis-dunbar.html"));
        pages.put(FCA, fixture("fca-accepted-firms.html"));
        pages.put("https://www.cbr.ru/rss/eventrss", fixture("cbr-eventrss.xml"));
        pages.put("https://www.cbr.ru/rss/engeventrss", fixture("cbr-engeventrss.xml"));
        pages.put("https://www.cbr.ru/rss/RssPress", fixture("cbr-rsspress.xml"));
        pages.put("https://www.cbr.ru/rss/EngRssPress", fixture("cbr-engrsspress.xml"));
        given(http.get(anyString(), any(URI.class), any(), anyInt())).willAnswer(call -> {
            URI uri = call.getArgument(1, URI.class);
            String body = pages.get(uri.toString());
            if (body == null) {
                throw new ConnectorException.Permanent(call.getArgument(0), 404, "not found " + uri);
            }
            return new RawHttpResponse(call.getArgument(0), uri.toString(), 200, body, "d".repeat(64), FETCHED, null);
        });
    }

    private static String fixture(String name) {
        try (InputStream stream = RegulatorsConnectorTest.class.getResourceAsStream("/connector/regulators/" + name)) {
            assertThat(stream).as(name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RegulatorsConnector connector() {
        return new RegulatorsConnector(
                new ConnectorsProperties("HorizonBot/1.0", null, null, null, 0, null, Map.of()),
                http,
                new CrawlDelay(Duration.ZERO),
                Clock.fixed(FETCHED, ZoneOffset.UTC));
    }

    private static CollectionRequest request(String query, List<String> targets) {
        return new CollectionRequest(
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                query,
                query,
                "ru",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 12, 31),
                Set.of(),
                500,
                targets);
    }

    private static List<Document> collected(RegulatorsConnector connector, CollectionRequest request) {
        try (var stream = connector.collect(request, null)) {
            return stream.documents().toList();
        }
    }

    private static List<Document> of(List<Document> documents, String venue) {
        return documents.stream()
                .filter(document -> document.venue().name().startsWith(venue))
                .toList();
    }

    @Test
    @DisplayName("проект BIS: британская «tokenisation» находится по «tokenization», дата — дата страницы")
    void bisProjectMatchesAcrossSpelling() {
        List<Document> documents = of(collected(connector(), request("tokenization", List.of())), "BIS");

        assertThat(documents)
                .extracting(Document::title)
                .contains("Project Agorá: Exploring tokenisation of cross-border payments");
        Document agora = documents.stream()
                .filter(document -> document.title().startsWith("Project Agorá"))
                .findFirst()
                .orElseThrow();
        assertThat(agora.publishedOn()).isEqualTo(LocalDate.of(2026, 5, 27));
        assertThat(agora.identifiers().url()).isEqualTo("https://www.bis.org/project/agora");
        assertThat(agora.sourceClass()).isEqualTo(SourceClass.NEWS);
        assertThat(agora.language()).isEqualTo("en");
        assertThat(agora.abstractText()).contains("multi-currency shared programmable platform");
        assertThat(agora.authors()).extracting(Author::organizationName).containsExactly("BIS Innovation Hub");
        assertThat(agora.provenance().sourceId()).isEqualTo(RegulatorsConnector.SOURCE_ID);
    }

    @Test
    @DisplayName("партнёры проекта BIS — отдельные организации")
    void bisPartnersAreOrganizations() {
        List<Document> documents =
                of(collected(connector(), request("cross-border payments compliance", List.of())), "BIS");

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).authors())
                .extracting(Author::organizationName)
                .contains("BIS Innovation Hub", "Reserve Bank of India", "Monetary Authority of Singapore");
        assertThat(documents.get(0).authors())
                .allSatisfy(author -> assertThat(author.organizationType()).isEqualTo(OrganizationType.GOVERNMENT));
    }

    @Test
    @DisplayName("фирма песочницы FCA — организация-компания; дата — конец периода, не позже обновления страницы")
    void fcaFirmIsTheCompany() {
        List<Document> documents = of(collected(connector(), request("stablecoin", List.of())), "FCA");

        assertThat(documents).isNotEmpty();
        assertThat(documents)
                .flatExtracting(Document::authors)
                .extracting(Author::organizationName)
                .contains("Revolut");
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.authors()).hasSize(1);
            assertThat(document.authors().get(0).organizationType()).isEqualTo(OrganizationType.COMPANY);
            assertThat(document.identifiers().url()).isEqualTo(FCA + "#section-list-of-accepted-firms");
            assertThat(document.abstractText()).containsIgnoringCase("stablecoin");
        });
        Document revolut = documents.stream()
                .filter(document -> document.authors().get(0).organizationName().equals("Revolut"))
                .findFirst()
                .orElseThrow();
        // «Stablecoin cohort 2026» → 31.12.2026, но страница обновлена 24.07.2026.
        assertThat(revolut.publishedOn()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    @DisplayName("фирма когортной модели датируется 31.12.2020: «Before 2021, we used a cohort model»")
    void cohortFirmsAreDatedByTheEndOfTheCohortModel() {
        List<Document> documents = of(collected(connector(), request("automated advice", List.of())), "FCA");

        assertThat(documents).isNotEmpty();
        Document standardLife = documents.stream()
                .filter(document -> document.title().startsWith("1825"))
                .findFirst()
                .orElseThrow();
        assertThat(standardLife.publishedOn()).isEqualTo(FcaSandbox.COHORTS_END);
    }

    @Test
    @DisplayName("событие Банка России находится и по-русски, и по-английски — одним документом")
    void bankOfRussiaEventIsOneDocumentInBothLanguages() {
        List<Document> russian =
                of(collected(connector(), request("цифровой рубль", List.of("digital ruble"))), "Банк России");
        List<Document> english = of(collected(connector(), request("digital ruble", List.of())), "Банк России");

        assertThat(russian).extracting(Document::title).contains("Цифровой рубль: старт использования");
        assertThat(english).extracting(Document::title).contains("Цифровой рубль: старт использования");
        assertThat(english).extracting(Document::title).doesNotContain("Digital ruble launched");
        Document launch = english.stream()
                .filter(document -> document.title().equals("Цифровой рубль: старт использования"))
                .findFirst()
                .orElseThrow();
        assertThat(launch.language()).isEqualTo("ru");
        assertThat(launch.abstractText()).contains("Digital ruble launched");
        assertThat(launch.identifiers().url()).startsWith("https://www.cbr.ru/press/event/?id=");
        assertThat(launch.authors()).extracting(Author::organizationName).containsExactly("Банк России");
        assertThat(launch.authors().get(0).organizationCountry()).isEqualTo("RU");
    }

    @Test
    @DisplayName("запрос не о финансах — законный ноль, без отказа")
    void unrelatedQueryIsZeroNotFailure() {
        assertThat(collected(connector(), request("edge ai", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("только записи в окне запроса")
    void onlyWithinWindow() {
        var request = new CollectionRequest(
                UUID.randomUUID(),
                "tokenization",
                "tokenization",
                "en",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2025, 12, 31),
                Set.of(),
                500,
                List.of());

        List<Document> documents = collected(connector(), request);

        assertThat(documents).allSatisfy(document -> assertThat(document.publishedOn())
                .isBeforeOrEqualTo(LocalDate.of(2025, 12, 31)));
        assertThat(documents).extracting(Document::title).noneMatch(title -> title.startsWith("Project Agorá"));
        // Проект вне окна не стоит запроса: страница Agorá (27.05.2026) не спрашивается.
        verify(http, never())
                .get(
                        anyString(),
                        ArgumentMatchers.eq(URI.create("https://www.bis.org/project/agora")),
                        any(),
                        anyInt());
    }

    @Test
    @DisplayName("площадка-заглушка выбывает, остальные собираются")
    void oneStubSiteDoesNotCostTheOthers() {
        pages.put(FCA, "<html><body>Access denied</body></html>");

        List<Document> documents = collected(connector(), request("stablecoin", List.of()));

        assertThat(of(documents, "FCA")).isEmpty();
        assertThat(documents).isNotEmpty();
    }

    @Test
    @DisplayName("если не ответила ни одна площадка — источник падает, а не приносит ноль")
    void allSitesFailingIsAFailure() {
        pages.put(BIS, "<html>maintenance</html>");
        pages.put(FCA, "<html>maintenance</html>");
        pages.put("https://www.cbr.ru/rss/eventrss", "<html>captcha</html>");
        pages.put("https://www.cbr.ru/rss/engeventrss", "<html>captcha</html>");
        pages.put("https://www.cbr.ru/rss/RssPress", "<html>captcha</html>");
        pages.put("https://www.cbr.ru/rss/EngRssPress", "<html>captcha</html>");

        assertThatThrownBy(() -> collected(connector(), request("stablecoin", List.of())))
                .isInstanceOf(ConnectorException.Retryable.class)
                .hasMessageContaining("Ни одна площадка");
    }

    @Test
    @DisplayName("robots.txt, называющий ИИ-краулеров, — отказ площадки без единого запроса к ней")
    void robotsNamingAiCrawlersIsARefusal() {
        String closed = "User-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nDisallow: /search/\n";
        pages.put("https://www.bis.org/robots.txt", closed);
        pages.put("https://www.fca.org.uk/robots.txt", closed);
        pages.put("https://www.cbr.ru/robots.txt", closed);

        assertThatThrownBy(() -> collected(connector(), request("stablecoin", List.of())))
                .isInstanceOf(ConnectorException.Permanent.class)
                .hasMessageContaining("ClaudeBot");
        verify(http, never()).get(anyString(), ArgumentMatchers.eq(URI.create(FCA)), any(), anyInt());
    }

    @Test
    @DisplayName("запрет в robots.txt для нашего агента — отказ площадки")
    void robotsDisallowIsARefusal() {
        pages.put("https://www.fca.org.uk/robots.txt", "User-agent: *\nDisallow: /firms/\n");

        List<Document> documents = collected(connector(), request("stablecoin", List.of()));

        assertThat(of(documents, "FCA")).isEmpty();
        verify(http, never()).get(anyString(), ArgumentMatchers.eq(URI.create(FCA)), any(), anyInt());
    }

    @Test
    @DisplayName("прочитанный перечень переиспользуется вторым запросом")
    void cataloguesAreCached() {
        RegulatorsConnector connector = connector();
        collected(connector, request("stablecoin", List.of()));
        collected(connector, request("open banking", List.of()));

        verify(http, Mockito.times(1)).get(anyString(), ArgumentMatchers.eq(URI.create(FCA)), any(), anyInt());
    }

    @Test
    @DisplayName("без настроенных площадок источник недоступен с причиной")
    void noSitesMeansUnavailable() {
        var settings = new ConnectorsProperties.ConnectorSettings(
                true, null, null, null, null, null, null, List.of("https://example.org/feed"), null);
        var connector = new RegulatorsConnector(
                new ConnectorsProperties(
                        null, null, null, null, 0, null, Map.of(RegulatorsConnector.SOURCE_ID, settings)),
                http);

        assertThat(connector.descriptor().available()).isFalse();
        assertThat(connector.descriptor().displayName()).isEqualTo("Регуляторы и центробанки");
    }
}
