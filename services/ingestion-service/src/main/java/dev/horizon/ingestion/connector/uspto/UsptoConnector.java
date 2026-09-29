package dev.horizon.ingestion.connector.uspto;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.connector.uspto.model.PpubsSearchResponse;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.RawPayloadStore;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * USPTO Patent Public Search (ppubs.uspto.gov) — опубликованные заявки (US-PGPUB) и выданные
 * патенты (USPAT) США.
 *
 * <p><b>Зачем вместо PatentsView.</b> PatentsView требует ключ, а ключ гражданам России не выдают:
 * патентный класс корпуса оставался пустым. Patent Public Search — поиск самого ведомства, тот, что
 * стоит за веб-приложением ppubs.uspto.gov; ключа не нужно, анонимная сессия открывается одним
 * запросом (проверено 2026-09-28 с рабочей машины и со стенда). Бонус к замене — заявки: PatentsView
 * отдавал только выданные патенты, а заявка публикуется через 18 месяцев после подачи, на годы
 * раньше выдачи, — это и есть ранний сигнал.
 *
 * <p><b>Как спрашиваем.</b> Сначала сессия: {@code POST /api/users/me/session} с телом {@code -1};
 * ключ приходит заголовком {@code x-access-token}, номер «дела» — в {@code userCase.caseId}. Сессия
 * одна на прогон сбора (открывается на первой странице первой формулировки) и переоткрывается
 * один раз, если площадка ответила {@code 401}/{@code 403}: сессия живёт полчаса простоя. Затем
 * {@code POST /api/searches/searchWithBeFamily} на каждую формулировку, язык запросов BRS:
 * {@code ("neuromorphic computing").ti,ab. AND @PD>=20200101<=20260928} — фраза в названии или
 * реферате, дата публикации в окне. Выдача — от новых публикаций к старым, по пятьдесят семейств на
 * страницу, не глубже двух страниц на формулировку: для ранней стадии ценны самые свежие.
 *
 * <p><b>Один документ на семейство.</b> Выдача приносит всё семейство: заявку и выданный по ней
 * патент с тем же названием. Два документа об одном изобретении удвоили бы его вес в счёте
 * публикаций, поэтому берётся самый ранний документ семейства в окне — то, что мир увидел первым, —
 * а число документов семейства записывается в текст.
 *
 * <p><b>Реферат не запрашивается.</b> Реферат и полный список изобретателей есть только в карточке
 * документа ({@code GET /api/patents/highlight/<guid>}), по запросу на каждый патент. При шести
 * запросах в минуту сто документов формулировки стоили бы семнадцати минут — весь бюджет анализа.
 * Совпадение формулировки с названием или рефератом при этом гарантирует сам поиск.
 *
 * <p><b>Вежливость.</b> Не больше шести запросов в минуту — пауза не короче десяти секунд между
 * любыми двумя запросами, без всплеска. У площадки есть ограничение на сессию: {@code 429} с
 * {@code "Too many requests"} (веб-приложение показывает на нём окно «There are too many requests in
 * your user session»); это отказ временный — ждём ({@code Retry-After}, если он есть, иначе 30 и 60
 * секунд) и повторяем, а после трёх попыток источник попадает в недоступные, а не в «ноль».
 * {@code robots.txt} у ppubs.uspto.gov нет ({@code 404} — ограничений нет), но проверяется перед
 * запросами, как у остальных сайтов.
 *
 * <p><b>Ноль и отказ различаются.</b> {@code error: null} и пустой {@code patents} — законный ноль.
 * Ошибка разбора запроса BRS приходит с кодом 200 и объектом {@code error} — это отказ, как и
 * ответ, в котором нет ни {@code patents}, ни {@code error}, сессия без ключа, {@code 4xx} и
 * исчерпанные повторы.
 *
 * <p><b>Срок.</b> Сессия 2026-09-28 объявляла: с 7 ноября 2026 PPUBS потребует вход под
 * учётной записью USPTO. Если анонимная сессия перестанет открываться, коннектор откажет с ясной
 * причиной, а не станет тихо возвращать ноль.
 */
public class UsptoConnector extends AbstractSourceConnector<PpubsSearchResponse.Raw> {

    public static final String SOURCE_ID = "uspto";
    private static final Logger log = LoggerFactory.getLogger(UsptoConnector.class);
    private static final String DEFAULT_BASE_URL = "https://ppubs.uspto.gov";
    private static final String SESSION_PATH = "/api/users/me/session";
    private static final String SEARCH_PATH = "/api/searches/searchWithBeFamily";
    private static final String TOKEN_HEADER = "x-access-token";
    /** Больше пятидесяти семейств на страницу веб-приложение не запрашивает — и мы тоже. */
    private static final int MAX_PAGE_SIZE = 50;

    static final int MAX_PAGES_PER_PHRASE = 2;
    private static final int MAX_REQUESTS_PER_MINUTE = 6;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Всё, кроме букв, цифр, пробела, дефиса и апострофа, — синтаксис BRS (скобки, {@code $}, {@code ?}, {@code @}). */
    private static final Pattern NOT_PHRASE = Pattern.compile("[^\\p{L}\\p{N}\\s'-]+");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final DateTimeFormatter BRS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final PpubsTransport transport;
    private final ObjectMapper objectMapper;
    private final RawPayloadStore rawStore;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration backoff;
    private final UsptoNormalizer normalizer = new UsptoNormalizer();
    private final URI sessionUri;
    private final URI searchUri;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final String userAgent;
    private final RobotsPolicy robots;

    private final Object lock = new Object();
    private Session session;
    private long lastRequestNanos;
    private boolean anyRequest;

    public UsptoConnector(
            ConnectorsProperties properties, ObjectMapper objectMapper, RawPayloadStore rawStore, Clock clock) {
        this(
                properties,
                objectMapper,
                rawStore,
                clock,
                PpubsTransport.http(SOURCE_ID, properties.connectTimeout(), properties.responseTimeout()),
                Sleeper.REAL,
                Duration.ofSeconds(30));
    }

    /** Для тестов: записанные ответы вместо сети и пауза, которая не ждёт. */
    UsptoConnector(
            ConnectorsProperties properties,
            ObjectMapper objectMapper,
            RawPayloadStore rawStore,
            Clock clock,
            PpubsTransport transport,
            Sleeper sleeper,
            Duration backoff) {
        var settings = properties.settings(SOURCE_ID);
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.rawStore = rawStore;
        this.clock = clock;
        this.sleeper = sleeper;
        this.backoff = backoff;
        String base = settings.baseUrlOr(DEFAULT_BASE_URL).replaceAll("/+$", "");
        this.sessionUri = URI.create(base + SESSION_PATH);
        this.searchUri = URI.create(base + SEARCH_PATH);
        this.pageSize = Math.min(settings.pageSizeOr(MAX_PAGE_SIZE), MAX_PAGE_SIZE);
        // Шесть в минуту — потолок, даже если в конфигурации ошиблись.
        this.requestsPerMinute =
                Math.min(settings.requestsPerMinuteOr(MAX_REQUESTS_PER_MINUTE), MAX_REQUESTS_PER_MINUTE);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        this.robots = new RobotsPolicy(this::robotsTxt);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "USPTO — патенты и заявки США",
                SourceClass.PATENT,
                Set.of(SourceClass.PATENT),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор uspto выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.PATENT);
    }

    @Override
    protected DocumentNormalizer<PpubsSearchResponse.Raw> normalizer() {
        return normalizer;
    }

    /**
     * Формулировки для BRS: без синтаксиса языка запросов и только с латиницей — полнотекстовый
     * индекс USPTO английский, и русская фраза потратила бы запрос на заведомый ноль.
     */
    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH).stream()
                .map(phrase -> SPACES.matcher(NOT_PHRASE.matcher(phrase).replaceAll(" "))
                        .replaceAll(" ")
                        .trim())
                .filter(phrase -> phrase.length() >= MIN_PHRASE_LENGTH)
                .filter(phrase -> LATIN.matcher(phrase).find())
                .distinct()
                .toList();
    }

    /** Фраза в названии или реферате, дата публикации — в окне запроса. */
    static String query(String phrase, LocalDate from, LocalDate to) {
        return "(\"" + phrase + "\").ti,ab. AND @PD>=" + from.format(BRS_DATE) + "<=" + to.format(BRS_DATE);
    }

    @Override
    protected SourcePage<PpubsSearchResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        Position position = Position.of(cursor);
        if (position.phrase() >= phrases.size()) {
            return SourcePage.empty();
        }
        String phrase = phrases.get(position.phrase());
        String query = query(phrase, request.windowFrom(), request.windowTo());
        int start = position.page() * pageSize;

        Exchange exchange = search(query, start, position.phrase() == 0 && position.page() == 0);
        PpubsSearchResponse body = parse(exchange.reply().body(), query);
        List<PpubsSearchResponse.Patent> patents = body.patents();
        Provenance provenance = provenance(exchange, query, start);

        List<PpubsSearchResponse.Raw> items = earliestPerFamily(patents, request).stream()
                .map(chosen -> new PpubsSearchResponse.Raw(
                        chosen.patent(),
                        phrase,
                        chosen.members(),
                        SOURCE_ID,
                        chosen.patent().guid(),
                        provenance))
                .toList();

        int families = body.numberOfFamilies() == null ? 0 : body.numberOfFamilies();
        boolean phraseDone = patents.isEmpty()
                || start + pageSize >= families
                || position.page() + 1 >= MAX_PAGES_PER_PHRASE;
        Position next = phraseDone ? new Position(position.phrase() + 1, 0) : new Position(position.phrase(), position.page() + 1);
        log.debug(
                "USPTO «{}» страница {}: семейств всего {}, строк {}, взято {}",
                phrase,
                position.page(),
                families,
                patents.size(),
                items.size());
        return new SourcePage<>(items, next.cursor(), phraseDone && next.phrase() >= phrases.size());
    }

    /** Представитель семейства — самый ранний документ в окне; документы без номера пропускаются. */
    private static List<Chosen> earliestPerFamily(List<PpubsSearchResponse.Patent> patents, CollectionRequest request) {
        Map<Object, Chosen> byFamily = new LinkedHashMap<>();
        for (PpubsSearchResponse.Patent patent : patents) {
            if (patent.guid() == null || patent.guid().isBlank()) {
                continue;
            }
            LocalDate published = UsptoNormalizer.date(patent.datePublished());
            // Окно площадка уже применила; повтор проверки — против иной трактовки границ. Строка без
            // даты идёт дальше, нормализатор её отвергнет — на виду в счётчике отвергнутых.
            if (published != null && !request.withinWindow(published)) {
                continue;
            }
            Object family = patent.familyIdentifierCur() == null ? patent.guid() : patent.familyIdentifierCur();
            Chosen current = byFamily.get(family);
            if (current == null) {
                byFamily.put(family, new Chosen(patent, published, 1));
            } else {
                boolean earlier = published != null && (current.published() == null || published.isBefore(current.published()));
                byFamily.put(
                        family,
                        earlier
                                ? new Chosen(patent, published, current.members() + 1)
                                : new Chosen(current.patent(), current.published(), current.members() + 1));
            }
        }
        return List.copyOf(byFamily.values());
    }

    private record Chosen(PpubsSearchResponse.Patent patent, LocalDate published, int members) {}

    private PpubsSearchResponse parse(String body, String query) {
        PpubsSearchResponse response;
        try {
            response = objectMapper.readValue(body, PpubsSearchResponse.class);
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "USPTO вернул не JSON на запрос " + query, e);
        }
        if (response.error() != null) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID,
                    200,
                    "USPTO отверг запрос %s: %s %s"
                            .formatted(query, response.error().errorCode(), response.error().errorMessage()));
        }
        if (response.patents() == null) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID, 200, "USPTO вернул не выдачу поиска (нет ни patents, ни error) на запрос " + query);
        }
        return response;
    }

    /** Тело поиска — то же, что шлёт веб-приложение ppubs; снято с него и проверено вживую. */
    String searchJson(String query, long caseId, int start) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("start", start);
        root.put("pageCount", pageSize);
        root.put("sort", "date_publ desc");
        root.put("docFamilyFiltering", "familyIdFiltering");
        root.put("searchType", 1);
        root.put("familyIdEnglishOnly", true);
        root.put("familyIdFirstPreferred", "US-PGPUB");
        root.put("familyIdSecondPreferred", "USPAT");
        root.put("familyIdThirdPreferred", "FIT");
        root.put("showDocPerFamilyPref", "showEnglish");
        root.put("queryId", 0);
        root.put("tagDocSearch", false);
        ObjectNode q = root.putObject("query");
        q.put("caseId", caseId);
        q.put("hl_snippets", "2");
        q.put("op", "OR");
        q.put("q", query);
        q.put("queryName", query);
        q.put("highlights", "1");
        q.put("qt", "brs");
        q.put("spellCheck", false);
        q.put("viewName", "tile");
        q.put("plurals", true);
        q.put("britishEquivalents", true);
        ArrayNode databases = q.putArray("databaseFilters");
        for (String database : List.of("US-PGPUB", "USPAT")) {
            ObjectNode filter = databases.addObject();
            filter.put("databaseName", database);
            filter.putArray("countryCodes");
        }
        q.put("searchType", 1);
        q.put("ignorePersist", true);
        q.put("userEnteredQuery", query);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 0, "Не удалось собрать запрос USPTO", e);
        }
    }

    // ---- обмен с площадкой -------------------------------------------------------------------

    private record Session(String token, long caseId) {}

    private record Exchange(PpubsTransport.Reply reply, Instant fetchedAt) {}

    /**
     * Поиск в текущей сессии. {@code freshSession} — первая страница прогона: сессия открывается
     * заново, чтобы счётчик площадки «запросов на сессию» начинался с нуля у каждого сбора.
     */
    private Exchange search(String query, int start, boolean freshSession) {
        synchronized (lock) {
            if (freshSession || session == null) {
                session = openSession();
            }
            boolean renewed = false;
            ConnectorException last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Instant fetchedAt;
                PpubsTransport.Reply reply;
                try {
                    fetchedAt = clock.instant();
                    reply = send("POST", searchUri, jsonHeaders(session.token()), searchJson(query, session.caseId(), start));
                } catch (ConnectorException.Retryable e) {
                    last = e;
                    waitBeforeRetry(attempt, null);
                    continue;
                }
                int status = reply.status();
                if ((status == 401 || status == 403) && !renewed) {
                    // Сессия истекла (полчаса простоя) — одна новая, и тот же запрос ещё раз.
                    log.info("USPTO: сессия отклонена ({}), открываю новую", status);
                    session = openSession();
                    renewed = true;
                    attempt--;
                    continue;
                }
                if (status == 429 || status >= 500) {
                    last = new ConnectorException.Retryable(
                            SOURCE_ID, status, "USPTO ответил %d на поиск %s: %s".formatted(status, query, brief(reply.body())));
                    waitBeforeRetry(attempt, reply.header("retry-after"));
                    continue;
                }
                if (status != 200) {
                    throw new ConnectorException.Permanent(
                            SOURCE_ID, status, "USPTO ответил %d на поиск %s: %s".formatted(status, query, brief(reply.body())));
                }
                return new Exchange(reply, fetchedAt);
            }
            throw last;
        }
    }

    /** Анонимная сессия: ключ — из заголовка, номер дела — из тела. */
    private Session openSession() {
        ConnectorException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PpubsTransport.Reply reply;
            try {
                reply = send("POST", sessionUri, jsonHeaders(null), "-1");
            } catch (ConnectorException.Retryable e) {
                last = e;
                waitBeforeRetry(attempt, null);
                continue;
            }
            int status = reply.status();
            if (status == 429 || status >= 500) {
                last = new ConnectorException.Retryable(SOURCE_ID, status, "USPTO ответил %d на открытие сессии".formatted(status));
                waitBeforeRetry(attempt, reply.header("retry-after"));
                continue;
            }
            if (status == 401 || status == 403) {
                throw new ConnectorException.Permanent(
                        SOURCE_ID,
                        status,
                        "USPTO не открыл анонимную сессию (%d): похоже, PPUBS начал требовать вход под учётной записью USPTO"
                                .formatted(status));
            }
            if (status != 200) {
                throw new ConnectorException.Permanent(SOURCE_ID, status, "USPTO ответил %d на открытие сессии".formatted(status));
            }
            String token = reply.header(TOKEN_HEADER);
            JsonNode caseId;
            try {
                caseId = objectMapper.readTree(reply.body()).path("userCase").path("caseId");
            } catch (JsonProcessingException e) {
                throw new ConnectorException.Permanent(SOURCE_ID, 200, "Сессия USPTO вернула не JSON", e);
            }
            if (token == null || token.isBlank() || !caseId.canConvertToLong()) {
                throw new ConnectorException.Permanent(
                        SOURCE_ID, 200, "Сессия USPTO без x-access-token или userCase.caseId — сменился контракт");
            }
            return new Session(token.trim(), caseId.asLong());
        }
        throw last;
    }

    private Map<String, String> jsonHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", userAgent);
        if (token != null) {
            headers.put(TOKEN_HEADER, token);
        }
        return headers;
    }

    /** Любой запрос к площадке: разрешение {@code robots.txt}, затем пауза темпа. */
    private PpubsTransport.Reply send(String method, URI uri, Map<String, String> headers, String body) {
        if (!robots.allows(uri, userAgent)) {
            throw new ConnectorException.Permanent(SOURCE_ID, 0, "robots.txt USPTO запрещает " + uri.getPath());
        }
        pace();
        return transport.exchange(method, uri, headers, body);
    }

    private String robotsTxt(URI uri) {
        synchronized (lock) {
            pace();
            PpubsTransport.Reply reply = transport.exchange("GET", uri, Map.of("User-Agent", userAgent), null);
            // 404 (так на 2026-09-28) и любой иной не-200 — файла нет, ограничений нет.
            return reply.status() == 200 ? reply.body() : null;
        }
    }

    /** Не чаще {@code requestsPerMinute}: пауза отсчитывается от предыдущего запроса к площадке. */
    private void pace() {
        long interval = Duration.ofMinutes(1).toNanos() / requestsPerMinute;
        long now = System.nanoTime();
        if (anyRequest) {
            long wait = lastRequestNanos + interval - now;
            if (wait > 0) {
                sleeper.sleep(Duration.ofNanos(wait));
            }
        }
        anyRequest = true;
        lastRequestNanos = Math.max(now, lastRequestNanos + (anyRequest ? interval : 0));
    }

    private void waitBeforeRetry(int attempt, String retryAfter) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        Duration wait = backoff.multipliedBy(1L << (attempt - 1));
        if (retryAfter != null) {
            try {
                wait = Duration.ofSeconds(Math.min(Long.parseLong(retryAfter.trim()), 300));
            } catch (NumberFormatException ignored) {
                // Дата вместо секунд — остаётся своя пауза.
            }
        }
        log.info("USPTO: временный отказ, попытка {} из {}, жду {} с", attempt, MAX_ATTEMPTS, wait.toSeconds());
        sleeper.sleep(wait);
    }

    private Provenance provenance(Exchange exchange, String query, int start) {
        byte[] bytes = exchange.reply().body().getBytes(StandardCharsets.UTF_8);
        String hash = Hashing.sha256Hex(bytes);
        String rawRef = rawStore.archive(SOURCE_ID, hash, exchange.fetchedAt(), bytes, "application/json")
                .orElse(null);
        // Запрос — POST, адреса у выдачи нет; в происхождение идёт адрес поиска с запросом и сдвигом,
        // этого достаточно, чтобы повторить обмен.
        String requestUrl = searchUri + "#q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&start=" + start;
        return new Provenance(SOURCE_ID, exchange.fetchedAt(), requestUrl, exchange.reply().status(), hash, rawRef);
    }

    private static String brief(String body) {
        String flat = body == null ? "" : SPACES.matcher(body).replaceAll(" ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    /** Пауза; в тестах подменяется, чтобы записанные ответы не ждали по десять секунд. */
    @FunctionalInterface
    interface Sleeper {

        Sleeper REAL = duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Permanent(SOURCE_ID, 0, "Ожидание перед запросом к USPTO прервано", e);
            }
        };

        void sleep(Duration duration);
    }

    /** Где остановился обход: номер формулировки и страница её выдачи. Курсор — {@code "фраза:страница"}. */
    record Position(int phrase, int page) {

        static Position of(Cursor cursor) {
            if (cursor == null || cursor.value() == null) {
                return new Position(0, 0);
            }
            String[] parts = cursor.value().split(":");
            try {
                return new Position(
                        Math.max(Integer.parseInt(parts[0]), 0),
                        parts.length > 1 ? Math.max(Integer.parseInt(parts[1]), 0) : 0);
            } catch (NumberFormatException e) {
                return new Position(0, 0);
            }
        }

        Cursor cursor() {
            return Cursor.ofValue(phrase + ":" + page);
        }
    }
}
