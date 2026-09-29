package dev.horizon.ingestion.connector.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Разбор подлинных лент RSS 2.0 и Atom 1.0 (`docs/01-analysis/27-connector-format-spec.md`, C1–C6).
 *
 * <p>Шестой источник и единственный без схемы вообще. Остальные пять отдают документированный
 * формат; лента — это два несовместимых диалекта, притворяющихся одним, с датами в разных
 * стандартах: RFC 822 в RSS («Tue, 05 Mar 2024 09:00:00 GMT») и RFC 3339 в Atom
 * («2024-03-05T09:00:00Z»).
 *
 * <p>Проверка идёт через настоящий коннектор с подменённым только HTTP: разбор Rome, отбор по окну и
 * запросу, отображение в документ — всё боевое. Собрать `RssItem` в тесте руками значило бы
 * проверять собственную выдумку, а вопрос здесь ровно один — работает ли оно на том, что реально
 * отдают ленты.
 *
 * <p><b>Почему это опаснее прочих источников.</b> Лента — самый слабый класс свидетельств и
 * единственный, где отсев происходит локально: API нельзя спросить, поэтому записи без даты, вне
 * окна и не совпавшие с запросом отбрасываются молча. Ошибка здесь не роняет ничего — корпус просто
 * оказывается меньше, и в отчёте это выглядит как «по направлению мало новостей».
 */
class RssFeedTest {

    private static final String RSS_FEED = "https://wired.example/enterprise/feed.xml";
    private static final String ATOM_FEED = "https://theregister.example/ai/atom.xml";

    private RssConnector connector;

    @BeforeEach
    void wireConnectorOverRecordedFeeds() throws IOException {
        ConnectorHttpClient http = Mockito.mock(ConnectorHttpClient.class);
        given(http.get(eq(RssConnector.SOURCE_ID), eq(URI.create(RSS_FEED)), any(), anyInt()))
                .willReturn(responseOf(RSS_FEED, "/connector/rss/feed-rss20.xml"));
        given(http.get(eq(RssConnector.SOURCE_ID), eq(URI.create(ATOM_FEED)), any(), anyInt()))
                .willReturn(responseOf(ATOM_FEED, "/connector/rss/feed-atom10.xml"));

        var settings = new ConnectorsProperties.ConnectorSettings(
                true, null, 30, null, null, null, null, List.of(RSS_FEED, ATOM_FEED), null);
        var properties =
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(RssConnector.SOURCE_ID, settings));
        connector = new RssConnector(properties, http);
    }

    private static RawHttpResponse responseOf(String url, String resource) throws IOException {
        try (InputStream stream = RssFeedTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("записанная лента " + resource).isNotNull();
            return new RawHttpResponse(
                    RssConnector.SOURCE_ID,
                    url,
                    200,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    "f".repeat(64),
                    Instant.parse("2026-03-01T10:00:00Z"),
                    null);
        }
    }

    private static CollectionRequest request() {
        return new CollectionRequest(
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                "speculative decoding",
                "speculative decoding",
                "en",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 12, 31),
                Set.of(SourceClass.NEWS),
                100,
                List.of());
    }

    private List<Document> collected() {
        try (var stream = connector.collect(request(), null)) {
            return stream.documents().toList();
        }
    }

    private Document titled(String fragment) {
        return collected().stream()
                .filter(document -> document.title().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет документа с «" + fragment + "» в заголовке"));
    }

    @Test
    @DisplayName("оба диалекта дают одну и ту же дату")
    void bothDialectsYieldTheSameDate() {
        // Сердце этого коннектора. RSS отдаёт «Tue, 05 Mar 2024 09:00:00 GMT», Atom —
        // «2024-03-05T09:00:00Z»; это один и тот же момент, записанный по двум разным стандартам.
        // Сломанный разбор одного из диалектов не выбрасывает исключение — лента просто перестаёт
        // давать записи, и половина новостного корпуса исчезает беззвучно.
        assertThat(titled("cuts inference latency").publishedOn()).isEqualTo(LocalDate.of(2024, 3, 5));
        assertThat(titled("lands in mainstream").publishedOn()).isEqualTo(LocalDate.of(2024, 3, 5));
    }

    @Test
    @DisplayName("дата публикации важнее даты правки")
    void thePublicationDateWinsOverTheUpdateOne() {
        // У записи Atom из образца `published` 2024-03-05, а `updated` — 2026-01-20: разрыв почти в
        // два года намеренно. Взять `updated` значило бы объявить свежей каждую статью, которую
        // издание когда-либо правило, — то есть выдать редакционную вычитку за новый сигнал.
        assertThat(titled("lands in mainstream").publishedOn()).isEqualTo(LocalDate.of(2024, 3, 5));
    }

    @Test
    @DisplayName("без даты публикации берётся дата правки")
    void theUpdateDateIsUsedWhenThereIsNoPublicationDate() {
        // Обычный случай для Atom: `published` необязателен, `updated` обязателен. Отбросить такие
        // записи значило бы потерять целые ленты — те, что не заполняют `published` вовсе.
        assertThat(titled("Draft models get smaller").publishedOn()).isEqualTo(LocalDate.of(2024, 6, 11));
    }

    @Test
    @DisplayName("запись без даты отбрасывается, а не датируется сегодняшним днём")
    void anEntryWithoutAnyDateIsDropped() {
        // В ленте RSS есть третья запись без `pubDate`, и она совпадает с запросом. Подставить ей
        // время сбора — самая соблазнительная правка здесь и худшая: новость неизвестного возраста
        // стала бы сегодняшней и попала прямо в индикатор новизны.
        assertThat(collected()).extracting(Document::title).noneMatch(title -> title.contains("Open-source draft"));
    }

    @Test
    @DisplayName("запись вне окна отбрасывается")
    void anEntryOutsideTheWindowIsDropped() {
        // Третья запись Atom — 2019 год, и она тоже совпадает с запросом. Окно её и исключает.
        assertThat(collected()).extracting(Document::title).noneMatch(title -> title.contains("An early look"));
    }

    @Test
    @DisplayName("запись, не совпавшая с запросом, отбрасывается")
    void anEntryThatDoesNotMatchTheQueryIsDropped() {
        // Ленту нельзя спросить, поэтому отбор идёт локально. Без него любой заголовок недели
        // попал бы в корпус: в образце это квартальная отчётность — новость того же издания, того
        // же автора и того же дня.
        assertThat(collected()).extracting(Document::title).noneMatch(title -> title.contains("Quarterly earnings"));
        assertThat(collected()).hasSize(3);
    }

    @Test
    @DisplayName("личность записи — её guid, а не ссылка")
    void theIdentityOfAnEntryIsItsGuidNotItsLink() {
        // Ссылка в образце несёт метки кампании. Взять её как идентификатор значит впустить ту же
        // статью повторно при каждой смене метки — а дубликаты одной новости выглядят как рост
        // интереса к теме.
        Document document = titled("cuts inference latency");

        assertThat(document.externalId()).isEqualTo("tag:wired.example,2024:enterprise/speculative-decoding");
        assertThat(document.identifiers().url()).contains("utm_source");
    }

    @Test
    @DisplayName("разметка и счётчики вычищены из текста")
    void markupAndTrackingAreStrippedFromTheText() {
        // Ленты возят HTML, ссылки «читать далее» и пиксельные счётчики. Отдать это в извлечение
        // терминов значит получить «href», «img» и «width» среди кандидатов в технологии.
        Document document = titled("cuts inference latency");

        assertThat(document.abstractText())
                .doesNotContain("<")
                .doesNotContain("href")
                .doesNotContain("&amp;")
                .contains("draft model proposes tokens & a larger model verifies");
    }

    @Test
    @DisplayName("площадка — издание, и неподписанная статья приписана ему же")
    void theVenueIsTheOutletAndUnsignedArticlesBelongToIt() {
        // Без площадки десять заметок одного издания выглядели бы десятью независимыми сигналами —
        // ровно то, чего правило достоверности не должно допускать.
        assertThat(titled("cuts inference latency").venue().name()).isEqualTo("Wired Enterprise");
        assertThat(titled("cuts inference latency").authors())
                .extracting("fullName")
                .containsExactly("Marina Volkova");
        assertThat(titled("Draft models get smaller").authors())
                .extracting("fullName")
                .containsExactly("The Register — AI");
    }

    @Test
    @DisplayName("рубрики ленты становятся предметными кодами")
    void feedCategoriesBecomeSubjectCodes() {
        assertThat(titled("cuts inference latency").topics())
                .extracting(DocumentTopic::code)
                .contains("AI infrastructure", "Inference");
    }

    @Test
    @DisplayName("обе ленты прочитаны за один сбор")
    void bothFeedsAreReadInOneCollection() {
        // Ленты — это страницы: курсор держит номер следующей. Остановка после первой означала бы,
        // что все издания, кроме первого, не читаются никогда.
        assertThat(collected())
                .extracting(document -> document.venue().name())
                .contains("Wired Enterprise", "The Register — AI");
    }

    @Test
    @DisplayName("недоступная лента не отменяет остальные")
    void oneDeadFeedDoesNotCostUsTheOthers() throws IOException {
        // BR-C7: частичный результат честнее пустого. Издание, лежащее в момент сбора, — обычное
        // дело, и терять из-за него остальные значило бы делать сбор тем ненадёжнее, чем больше
        // источников настроено.
        ConnectorHttpClient http = Mockito.mock(ConnectorHttpClient.class);
        willThrow(new ConnectorException.Retryable(RssConnector.SOURCE_ID, 503, "лента недоступна"))
                .given(http)
                .get(eq(RssConnector.SOURCE_ID), eq(URI.create(RSS_FEED)), any(), anyInt());
        given(http.get(eq(RssConnector.SOURCE_ID), eq(URI.create(ATOM_FEED)), any(), anyInt()))
                .willReturn(responseOf(ATOM_FEED, "/connector/rss/feed-atom10.xml"));

        var settings = new ConnectorsProperties.ConnectorSettings(
                true, null, 30, null, null, null, null, List.of(RSS_FEED, ATOM_FEED), null);
        var properties =
                new ConnectorsProperties(null, null, null, null, 0, null, Map.of(RssConnector.SOURCE_ID, settings));

        try (var stream = new RssConnector(properties, http).collect(request(), null)) {
            // Пустой результат прошёл бы проверку «нет записей мёртвой ленты» — поэтому живая лента
            // проверяется на присутствие, а не только мёртвая на отсутствие.
            assertThat(stream.documents())
                    .extracting(document -> document.venue().name())
                    .containsExactly("The Register — AI", "The Register — AI");
        }
    }
}
