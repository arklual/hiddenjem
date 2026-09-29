package dev.horizon.ingestion.connector.openalex;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.openalex.model.OpenAlexPage;
import dev.horizon.ingestion.connector.openalex.model.OpenAlexWork;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.JsonBodies;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * OpenAlex {@code /works} — the bibliographic backbone of the corpus.
 *
 * <p><b>Paging: opaque cursor.</b> {@code cursor=*} starts a scroll and each response carries
 * {@code meta.next_cursor}. Unlike offset paging this is stable while the index changes underneath
 * us and has no deep-paging cliff, which matters when a broad query matches a hundred thousand
 * works.
 *
 * <p><b>Polite pool.</b> Every request carries {@code mailto}; OpenAlex routes identified traffic to
 * a separate, faster pool. Politeness is literally rewarded here (BR-C3, NFR-S12).
 *
 * <p><b>Rate limit</b> defaults to 60 requests/minute, comfortably inside OpenAlex's published
 * ceiling of 10 per second and 100 000 per day.
 */
public class OpenAlexConnector extends AbstractSourceConnector<OpenAlexWork.Raw> {

    /** Коды предметных словарей arXiv: {@code cs.LG}, {@code stat.ML}, {@code quant-ph.x}. */
    private static final Pattern CLASSIFICATION_CODE = Pattern.compile("[a-z-]{2,12}\\.[a-zA-Z-]{2,12}");
    /** Кириллица или иероглифы в поисковых терминах — искать по заголовку и аннотации. */
    private static final Pattern NON_LATIN = Pattern.compile("[\\p{IsCyrillic}\\p{IsHan}]");


    public static final String SOURCE_ID = "openalex";
    private static final String DEFAULT_BASE_URL = "https://api.openalex.org/works";
    private static final String CURSOR_START = "*";

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final OpenAlexNormalizer normalizer = new OpenAlexNormalizer();
    private final String baseUrl;
    private final String mailto;
    /** Ключ OpenAlex; пустой — работа в бесплатном бюджете адреса. */
    private final OpenAlexKeyRing apiKeys;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public OpenAlexConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.mailto = properties.contactEmail();
        this.apiKeys = new OpenAlexKeyRing(settings.apiKey(), System.getenv("HORIZON_OPENALEX_API_KEYS"));
        this.pageSize = Math.min(settings.pageSizeOr(200), 200);
        this.requestsPerMinute = settings.requestsPerMinuteOr(60);
        this.enabled = settings.enabledOr(true);
    }

    List<String> configuredKeys() {
        return apiKeys.configured();
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "OpenAlex",
                SourceClass.JOURNAL_ARTICLE,
                Set.of(
                        SourceClass.JOURNAL_ARTICLE,
                        SourceClass.PREPRINT,
                        SourceClass.STANDARD,
                        SourceClass.ANALYST_REPORT),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор openalex выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && request.acceptsAnyOf(descriptor().providedClasses());
    }

    @Override
    protected DocumentNormalizer<OpenAlexWork.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<OpenAlexWork.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        String cursorValue = cursor == null || cursor.value() == null ? CURSOR_START : cursor.value();
        String nonLatin = nonLatinSearch(request);
        String filter = nonLatin == null ? filter(request) : filter(request) + ",title_and_abstract.search:" + nonLatin;
        var uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("filter", filter)
                .queryParam("per-page", pageSize)
                .queryParam("cursor", cursorValue)
                .queryParam("mailto", mailto);
        // Без ключа суточный бюджет OpenAlex — $0,10 на адрес: сто поисковых запросов. Замер
        // заголовков ответа стенду 2026-09-18 — осталось 42 кредита из 1000, и следующий сбор
        // получил 429 на третьей странице. Бесплатный ключ поднимает бюджет до $1 в сутки.
        if (!request.isWildcard() && nonLatin == null) {
            uriBuilder.queryParam("search", searchExpression(request.upstreamTerms()));
        }
        RawHttpResponse response;
        while (true) {
            String key = apiKeys.current();
            URI uri = uriBuilder.build().encode().toUri();
            try {
                var headers = key.isEmpty()
                        ? Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        : Map.of(
                                HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE,
                                HttpHeaders.AUTHORIZATION, "Bearer " + key);
                response = http.get(
                        SOURCE_ID, uri, headers, requestsPerMinute);
                break;
            } catch (ConnectorException exception) {
                if (exception.httpStatus() == 429 && apiKeys.exhausted(key)) {
                    continue;
                }
                throw exception;
            }
        }
        OpenAlexPage page = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), OpenAlexPage.class);

        List<OpenAlexWork.Raw> items = new ArrayList<>(page.results().size());
        for (OpenAlexWork work : page.results()) {
            items.add(new OpenAlexWork.Raw(work, SOURCE_ID, externalIdOf(work), response.provenance()));
        }
        String nextCursor = page.nextCursor();
        boolean last = nextCursor == null || nextCursor.isBlank() || items.isEmpty();
        return new SourcePage<>(items, Cursor.ofValue(last ? cursorValue : nextCursor), last);
    }

    /**
     * Цели направления — фразами через ИЛИ, а не мешком слов.
     *
     * <p>Мешок слов был ошибкой в предположении, записанном в {@link CollectionRequest#upstreamQuery()}:
     * «OpenAlex принимает мешок слов и сам решает, что с ним делать». Он решает — соединяет слова
     * через И. Замер 2026-09-18 с настоящим окном 2019–2026: запрос «ai artificial intelligence
     * computer vision cs.AI … stat.ML» находит <b>30</b> работ, те же цели фразами через {@code OR}
     * — 5 420 503. Источник с лучшими аффилиациями из всех приносил в корпус тридцать документов из
     * трёх тысяч, и правило «две независимые организации» теряло опору ровно там, где она есть.
     *
     * <p>Классификационные коды arXiv ({@code cs.LG}, {@code stat.ML}) исключаются: в названиях и
     * аннотациях OpenAlex их нет. Одна цель — запрос аналитика на языке источника — уходит как есть:
     * подменять его формулировку значило бы отвечать не на его вопрос.
     */
    static String searchExpression(List<String> upstreamTerms) {
        List<String> terms = upstreamTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .filter(term -> !CLASSIFICATION_CODE.matcher(term).matches())
                .distinct()
                .toList();
        if (terms.size() <= 1) {
            return terms.isEmpty() ? String.join(" ", upstreamTerms).trim() : terms.get(0);
        }
        return terms.stream()
                .map(term -> "\"" + term.replace("\"", "") + "\"")
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Русский или китайский поиск — только по заголовку и аннотации.
     *
     * <p>Параметр {@code search} ищет и по полному тексту, и на нелатинских письменностях это
     * превращается в шум: замер 2026-09-19 — по «периферийным вычислениям» китайскими терминами
     * пришли МРТ коленного сустава и осадки на Тибете, русскими — уроки COVID-19 для здравоохранения.
     * Фильтр {@code title_and_abstract.search} с терминами в кавычках находит меньше, но о том:
     * «"федеративное обучение"» — пятнадцать работ, и все про федеративное обучение. Для слабых
     * сигналов это верная сторона компромисса.
     *
     * @return выражение поиска, если термины запроса не латиница; {@code null} — обычный поиск
     */
    static String nonLatinSearch(CollectionRequest request) {
        if (request.isWildcard()) {
            return null;
        }
        List<String> terms = request.upstreamTerms();
        if (terms.size() != 1 || !NON_LATIN.matcher(terms.get(0)).find()) {
            return null;
        }
        // Запятая разделяет фильтры OpenAlex и внутри значения недопустима.
        return terms.get(0).replace(",", " ").trim();
    }

    /** Window plus the constraints that keep obvious noise out of the corpus. */
    private static String filter(CollectionRequest request) {
        return "from_publication_date:%s,to_publication_date:%s".formatted(request.windowFrom(), request.windowTo());
    }

    /** OpenAlex ids are URLs ({@code https://openalex.org/W123}); the local part is the identity. */
    static String externalIdOf(OpenAlexWork work) {
        String id = work.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("OpenAlex work without an id");
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
    }
}
