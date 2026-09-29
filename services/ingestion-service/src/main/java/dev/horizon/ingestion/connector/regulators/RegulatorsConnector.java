package dev.horizon.ingestion.connector.regulators;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.regulators.model.RegulatorNotice;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Регуляторы и центробанки: программы инноваций как ранний сигнал финтеха с официальной датой.
 *
 * <p>Жюри заведомо спросит про финтех, а научная литература о нём запаздывает на годы. Ранние
 * официальные сигналы — у регуляторов: проект Инновационного центра BIS (Agorá — токенизация
 * трансграничных платежей, Mandala — правила для них же), фирма, принятая в песочницу FCA со своим
 * продуктом (первые выпуски стейблкоинов в Великобритании), событие Банка России (цифровой рубль,
 * правила рынка криптовалют). Один источник с тремя площадками, как {@code industry} с шестью
 * изданиями: это один вид свидетельства — «регулятор официально занялся темой», — и одна запись в
 * перечне источников; отказ одной площадки не роняет две другие, а отказ всех трёх — роняет
 * источник.
 *
 * <p><b>Площадки.</b> Адреса — в настройке {@code feeds}; площадка узнаётся по хосту:
 *
 * <ul>
 *   <li>{@code bis.org} — перечень {@code /about/innovation-hub/projects} (и его страницы
 *       {@code ?page=N}), затем страница каждого проекта в окне запроса ({@link BisProjects});
 *   <li>{@code fca.org.uk} — одна страница с таблицей всех фирм песочницы ({@link FcaSandbox});
 *   <li>{@code cbr.ru} — ленты RSS, русские и английские парами ({@link CbrFeeds}).
 * </ul>
 *
 * <p><b>Поиска нет — сопоставление здесь.</b> Все три площадки отдают перечень целиком. Запись
 * берётся, если в её заголовке или тексте подряд и целыми словами стоит одна из формулировок
 * запроса: цели словаря ({@code SearchPhrases}) и русская формулировка аналитика, как у Хабра. Правило
 * — в {@link PhraseMatcher}. Поэтому же перечни кэшируются в экземпляре коннектора (перечни и страницы
 * — на шесть часов, ленты — на пятнадцать минут): второй запрос за день не обходит тридцать страниц
 * BIS заново, а сопоставляет уже прочитанное.
 *
 * <p><b>Что можно.</b> {@code robots.txt} всех трёх (28.09.2026) не запрещает ни перечней, ни
 * страниц проектов, ни лент; запрещены поиск ({@code /search/}) и у FCA — адреса с параметрами, которых
 * мы не спрашиваем. Проверяется перед каждой площадкой: запрет или правило, называющее ИИ-краулеров
 * поимённо, — отказ площадки с
 * причиной, без единого запроса к ней. Пауза — три секунды между запросами к одному хосту:
 * {@code Crawl-delay} никто из трёх не пишет, а страницы BIS идут десятками подряд.
 *
 * <p><b>Ноль и отказ различаются.</b> Площадка, которая не ответила или ответила не тем (перечень без
 * карточек, страница без таблицы, лента, не разбираемая как RSS), выбывает и попадает в журнал.
 * Если не ответила ни одна — прогон источника падает: иначе отказ всех трёх выглядел бы как
 * «регуляторы темой не занимались».
 */
public class RegulatorsConnector extends AbstractSourceConnector<RegulatorNotice> {

    public static final String SOURCE_ID = "regulators";
    public static final List<String> DEFAULT_FEEDS = List.of(
            "https://www.bis.org/about/innovation-hub/projects",
            "https://www.fca.org.uk/firms/innovation/regulatory-sandbox/accepted-firms",
            "https://www.cbr.ru/rss/eventrss",
            "https://www.cbr.ru/rss/engeventrss",
            "https://www.cbr.ru/rss/RssPress",
            "https://www.cbr.ru/rss/EngRssPress");

    static final String BIS_HUB = "BIS Innovation Hub";
    static final String FCA_SANDBOX = "FCA Regulatory Sandbox";
    static final String BANK_OF_RUSSIA = "Банк России";

    private static final Logger log = LoggerFactory.getLogger(RegulatorsConnector.class);
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Страниц перечня BIS не больше десяти: сейчас их четыре, по десять проектов. */
    static final int MAX_BIS_PAGES = 10;

    private static final int BIS_PAGE_SIZE = 10;
    private static final Duration CATALOGUE_TTL = Duration.ofHours(6);
    private static final Duration FEED_TTL = Duration.ofMinutes(15);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Pattern AI_CRAWLER_AGENT = Pattern.compile(
            "(?im)^\\s*user-agent\\s*:\\s*(claudebot|claude-user|claude-searchbot|claude-code|claude-web|anthropic-ai)\\b");
    private static final String ANCHOR_FCA = "#section-list-of-accepted-firms";

    /** Площадка — узнаётся по хосту адреса из {@code feeds}. */
    enum Site {
        BIS,
        FCA,
        CBR;

        static Site of(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("bis.org") || host.endsWith(".bis.org")) {
                return BIS;
            }
            if (host.equals("fca.org.uk") || host.endsWith(".fca.org.uk")) {
                return FCA;
            }
            if (host.equals("cbr.ru") || host.endsWith(".cbr.ru")) {
                return CBR;
            }
            return null;
        }
    }

    private final ConnectorHttpClient http;
    private final RegulatorsNormalizer normalizer = new RegulatorsNormalizer();
    private final Map<Site, List<URI>> sites;
    private final List<Site> order;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    private final CrawlDelay crawlDelay;
    private final Clock clock;
    /** Тело {@code robots.txt} по хосту — чтобы увидеть правила про ИИ-краулеров. */
    private final Map<String, String> robotsBodies = new ConcurrentHashMap<>();
    /** Прочитанные страницы и ленты: перечни регуляторов меняются раз в недели, а спрашиваются часто. */
    private final Map<URI, RawHttpResponse> cache = new ConcurrentHashMap<>();

    public RegulatorsConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(3)), Clock.systemUTC());
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанных страниц не ждал. */
    RegulatorsConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay, Clock clock) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.crawlDelay = crawlDelay;
        this.clock = clock;
        List<String> feeds = settings.feeds().isEmpty() ? DEFAULT_FEEDS : settings.feeds();
        Map<Site, List<URI>> bySite = new EnumMap<>(Site.class);
        for (String feed : feeds) {
            if (feed == null || feed.isBlank()) {
                continue;
            }
            URI uri = URI.create(feed.trim());
            Site site = Site.of(uri);
            if (site == null) {
                log.warn("Regulators feed {} belongs to no known regulator and is ignored", feed);
                continue;
            }
            bySite.computeIfAbsent(site, key -> new ArrayList<>()).add(uri);
        }
        this.sites = bySite;
        this.order = List.copyOf(bySite.keySet());
        // Пауза в три секунды — не больше двадцати запросов в минуту к хосту.
        this.requestsPerMinute = settings.requestsPerMinuteOr(20);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        this.robots = new RobotsPolicy(uri -> {
            try {
                crawlDelay.await(uri.getHost());
                String body =
                        http.get(keyOf(uri), uri, Map.of(), requestsPerMinute).body();
                if (body != null) {
                    robotsBodies.put(authorityOf(uri), body);
                }
                return body;
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Регуляторы и центробанки",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Коннектор regulators выключен конфигурацией");
        }
        if (order.isEmpty()) {
            return descriptor.unavailable("Не настроено ни одной площадки регуляторов "
                    + "(horizon.connectors.sources.regulators.feeds: bis.org, fca.org.uk, cbr.ru)");
        }
        return descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<RegulatorNotice> normalizer() {
        return normalizer;
    }

    /** Формулировки для сопоставления: русская формулировка аналитика, если она русская, и цели словаря. */
    static List<String> phrases(CollectionRequest request) {
        var phrases = new LinkedHashSet<String>();
        if (SearchFeeds.hasCyrillic(request.query())) {
            phrases.add(request.query().trim());
        }
        phrases.addAll(SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH));
        return List.copyOf(phrases);
    }

    @Override
    protected SourcePage<RegulatorNotice> fetchPage(CollectionRequest request, Cursor cursor) {
        PhraseMatcher matcher = new PhraseMatcher(phrases(request));
        if (matcher.isEmpty() || order.isEmpty()) {
            return SourcePage.empty();
        }
        Position position = Position.of(cursor);
        if (position.index >= order.size()) {
            return finish(position, List.of());
        }
        Site site = order.get(position.index);
        List<RegulatorNotice> items = List.of();
        Position next;
        try {
            items = switch (site) {
                case BIS -> bis(request, matcher, sites.get(site).get(0));
                case FCA -> fca(request, matcher, sites.get(site).get(0));
                case CBR -> cbr(request, matcher, sites.get(site));};
            log.info("Regulators: {} matched {} records for '{}'", site, items.size(), request.query());
            next = position.withAnswer();
        } catch (Refused e) {
            log.warn("Regulators: {} is skipped: {}", site, e.getMessage());
            next = position.withRefusal(e.getMessage());
        } catch (RuntimeException e) {
            // Не только отказы HTTP: открытый предохранитель хоста бросает своё исключение, и оно не
            // должно ронять две другие площадки.
            log.warn("Regulators: {} failed and is dropped for this run: {}", site, e.getMessage());
            next = position.withFailure(site + ": " + e.getMessage());
        }
        next = next.advance();
        if (next.index >= order.size()) {
            return finish(next, items);
        }
        return new SourcePage<>(items, next.toCursor(), false);
    }

    // --- BIS -------------------------------------------------------------------------------------

    private List<RegulatorNotice> bis(CollectionRequest request, PhraseMatcher matcher, URI listing) {
        List<BisProjects.Card> cards = new ArrayList<>();
        for (int page = 0; page < MAX_BIS_PAGES; page++) {
            URI uri = page == 0 ? listing : URI.create(listing + "?page=" + page);
            RawHttpResponse response = fetch(uri, CATALOGUE_TTL);
            List<BisProjects.Card> found = BisProjects.cards(listing, response.body());
            if (page == 0 && found.isEmpty()) {
                // Перечень без единой карточки — это не «проектов нет», а сменившаяся разметка или
                // страница-заглушка.
                throw new ConnectorException.Permanent(
                        SOURCE_ID, response.status(), "BIS projects listing has no project cards: " + uri);
            }
            cards.addAll(found);
            if (found.size() < BIS_PAGE_SIZE) {
                break;
            }
        }
        List<RegulatorNotice> notices = new ArrayList<>();
        int asked = 0;
        int failed = 0;
        for (BisProjects.Card card : cards) {
            // Дата карточки — та же дата страницы проекта: проект вне окна не стоит запроса.
            if (card.date() != null && !request.withinWindow(card.date())) {
                continue;
            }
            asked++;
            RawHttpResponse response;
            BisProjects.Project project;
            try {
                response = fetch(card.url(), CATALOGUE_TTL);
                project = BisProjects.project(response.body(), card.date());
            } catch (Refused e) {
                throw e;
            } catch (RuntimeException e) {
                log.debug("BIS project page {} failed: {}", card.url(), e.getMessage());
                failed++;
                continue;
            }
            if (project == null) {
                log.debug("BIS project page {} has no title or date", card.url());
                failed++;
                continue;
            }
            if (!request.withinWindow(project.publishedOn())) {
                continue;
            }
            String title = project.description() == null
                            || project.title()
                                    .toLowerCase(Locale.ROOT)
                                    .contains(project.description().toLowerCase(Locale.ROOT))
                    ? project.title()
                    : project.title() + ": " + project.description();
            String text = join(project.description(), project.text());
            if (!matcher.matches(title + " " + (text == null ? "" : text))) {
                continue;
            }
            List<RegulatorNotice.Organization> organizations = new ArrayList<>();
            organizations.add(new RegulatorNotice.Organization(BIS_HUB, OrganizationType.GOVERNMENT, "CH"));
            for (String partner : project.partners()) {
                organizations.add(new RegulatorNotice.Organization(partner, OrganizationType.GOVERNMENT, null));
            }
            String slug = card.url().getPath().substring(card.url().getPath().lastIndexOf('/') + 1);
            notices.add(new RegulatorNotice(
                    "bis",
                    title,
                    text,
                    card.url().toString(),
                    project.publishedOn(),
                    "en",
                    organizations,
                    BIS_HUB,
                    SOURCE_ID,
                    "bis:" + slug,
                    provenanceOf(response)));
        }
        if (asked > 0 && failed == asked) {
            throw new ConnectorException.Retryable(
                    SOURCE_ID, 0, "No BIS project page could be read (%d asked)".formatted(asked));
        }
        return notices;
    }

    // --- FCA -------------------------------------------------------------------------------------

    private List<RegulatorNotice> fca(CollectionRequest request, PhraseMatcher matcher, URI page) {
        RawHttpResponse response = fetch(page, CATALOGUE_TTL);
        FcaSandbox.Page parsed = FcaSandbox.parse(response.body());
        if (parsed.firms().isEmpty()) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID, response.status(), "FCA sandbox page has no table of accepted firms: " + page);
        }
        Provenance provenance = provenanceOf(response);
        List<RegulatorNotice> notices = new ArrayList<>();
        for (FcaSandbox.Firm firm : parsed.firms()) {
            if (firm.date() == null || !request.withinWindow(firm.date())) {
                continue;
            }
            if (!matcher.matches(firm.name() + " " + firm.description())) {
                continue;
            }
            // У одной фирмы бывает несколько продуктов (у Barclays — два): заголовок и
            // идентификатор включают описание, иначе второй поглощался бы первым.
            String title = firm.name() + ": " + shorten(firm.description(), 160);
            String text = firm.description() + " Accepted onto the FCA Regulatory Sandbox: " + firm.accepted() + ".";
            notices.add(new RegulatorNotice(
                    "fca",
                    title,
                    text,
                    page + ANCHOR_FCA,
                    firm.date(),
                    "en",
                    List.of(new RegulatorNotice.Organization(firm.name(), OrganizationType.COMPANY, null)),
                    FCA_SANDBOX,
                    SOURCE_ID,
                    "fca:"
                            + Hashing.sha256Hex(firm.name() + "|" + firm.description())
                                    .substring(0, 24),
                    provenance));
        }
        return notices;
    }

    // --- Банк России -----------------------------------------------------------------------------

    private List<RegulatorNotice> cbr(CollectionRequest request, PhraseMatcher matcher, List<URI> feeds) {
        List<CbrFeeds.Fetched> fetched = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (URI feed : feeds) {
            try {
                RawHttpResponse response = fetch(feed, FEED_TTL);
                SearchFeeds.Feed parsed =
                        SearchFeeds.parse(SOURCE_ID, feed.toString(), withoutByteOrderMark(response.body()));
                fetched.add(new CbrFeeds.Fetched(feed.toString(), parsed, provenanceOf(response)));
            } catch (Refused e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Bank of Russia feed {} failed: {}", feed, e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (fetched.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        List<RegulatorNotice> notices = new ArrayList<>();
        for (CbrFeeds.Merged merged : CbrFeeds.merge(fetched)) {
            SearchFeeds.Entry entry = merged.entry();
            // Время лент — московское (+0300): день публикации — московский, а не UTC.
            LocalDate date = entry.publishedAt().atZone(MOSCOW).toLocalDate();
            if (!request.withinWindow(date)) {
                continue;
            }
            String title = HtmlText.of(entry.title());
            String text = HtmlText.of(join(
                    entry.description(),
                    merged.translation() == null ? null : merged.translation().title(),
                    merged.translation() == null ? null : merged.translation().description()));
            if (title == null || !matcher.matches(title + " " + (text == null ? "" : text))) {
                continue;
            }
            notices.add(new RegulatorNotice(
                    "cbr",
                    title,
                    text,
                    entry.link(),
                    date,
                    merged.russian() ? "ru" : "en",
                    List.of(new RegulatorNotice.Organization(BANK_OF_RUSSIA, OrganizationType.GOVERNMENT, "RU")),
                    BANK_OF_RUSSIA + " — " + merged.origin().feed().title(),
                    SOURCE_ID,
                    "cbr:" + entry.externalId(),
                    merged.origin().provenance()));
        }
        return notices;
    }

    // --- Общее -----------------------------------------------------------------------------------

    /**
     * Страница или лента: из кэша, если он свежий, иначе — после проверки {@code robots.txt} и паузы.
     *
     * @throws Refused площадка запрещает этот адрес или называет ИИ-краулеров
     */
    private RawHttpResponse fetch(URI uri, Duration ttl) {
        RawHttpResponse cached = cache.get(uri);
        if (cached != null && cached.fetchedAt().plus(ttl).isAfter(clock.instant())) {
            return cached;
        }
        if (!robots.allows(uri, userAgent)) {
            throw new Refused("robots.txt %s запрещает %s".formatted(uri.getHost(), uri.getPath()));
        }
        String robotsBody = robotsBodies.get(authorityOf(uri));
        if (robotsBody != null) {
            Matcher agent = AI_CRAWLER_AGENT.matcher(robotsBody);
            if (agent.find()) {
                throw new Refused("robots.txt %s содержит правило для агента %s — площадка высказалась об ИИ-краулерах, "
                                .formatted(uri.getHost(), agent.group(1))
                        + "и мы к ней не ходим");
            }
        }
        crawlDelay.await(uri.getHost());
        RawHttpResponse response = http.get(
                keyOf(uri),
                uri,
                Map.of(HttpHeaders.ACCEPT, "text/html, application/rss+xml, application/xml;q=0.9, */*;q=0.8"),
                requestsPerMinute);
        cache.put(uri, response);
        return response;
    }

    /** Происхождение — от имени источника, а не ключа хоста: документ принадлежит {@code regulators}. */
    private static Provenance provenanceOf(RawHttpResponse response) {
        return new Provenance(
                SOURCE_ID,
                response.fetchedAt(),
                response.requestUrl(),
                response.status(),
                response.payloadHash(),
                response.rawRef());
    }

    /** Последняя позиция: если не ответила ни одна площадка — это отказ, а не ноль. */
    private SourcePage<RegulatorNotice> finish(Position position, List<RegulatorNotice> items) {
        if (position.answered == 0 && (position.failed > 0 || position.refused > 0)) {
            String reason = "Ни одна площадка регуляторов не ответила: отказов %d, запретов %d — %s"
                    .formatted(position.failed, position.refused, position.reason);
            if (position.failed == 0) {
                // Запрет площадки не пройдёт от повтора: это решение владельца сайта.
                throw new ConnectorException.Permanent(SOURCE_ID, 0, reason);
            }
            throw new ConnectorException.Retryable(SOURCE_ID, 0, reason);
        }
        return new SourcePage<>(items, position.toCursor(), true);
    }

    /**
     * Ленты Банка России начинаются с метки порядка байтов UTF-8, и разбор XML на ней падает: без
     * этой правки все четыре ленты выглядели бы отказом площадки.
     */
    static String withoutByteOrderMark(String body) {
        return body != null && body.startsWith("\uFEFF") ? body.substring(1) : body;
    }

    private static String join(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                joined.append(joined.isEmpty() ? "" : " ").append(part.trim());
            }
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private static String shorten(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int space = text.lastIndexOf(' ', max);
        return text.substring(0, space > max / 2 ? space : max) + "…";
    }

    private static String authorityOf(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** Ограничитель и предохранитель — на хост: отказавший регулятор не тормозит остальных. */
    private static String keyOf(URI uri) {
        return SOURCE_ID + ":" + uri.getHost();
    }

    /** Площадка отказала сама — запретом в {@code robots.txt}; не неисправность и не ноль. */
    static final class Refused extends RuntimeException {

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Какая площадка следующая и что известно о пройденных: ответы, отказы, запреты и причина
     * первого отказа. Всё в курсоре, потому что экземпляр коннектора один на все прогоны.
     */
    record Position(int index, int answered, int failed, int refused, String reason) {

        static Position of(Cursor cursor) {
            if (cursor == null || cursor.value() == null || cursor.value().isBlank()) {
                return new Position(0, 0, 0, 0, "");
            }
            try {
                String[] parts = cursor.value().split(";", 5);
                return new Position(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        parts.length > 4 ? parts[4] : "");
            } catch (RuntimeException e) {
                return new Position(0, 0, 0, 0, "");
            }
        }

        Cursor toCursor() {
            return Cursor.ofValue("%d;%d;%d;%d;%s".formatted(index, answered, failed, refused, reason));
        }

        Position advance() {
            return new Position(index + 1, answered, failed, refused, reason);
        }

        Position withAnswer() {
            return new Position(index, answered + 1, failed, refused, reason);
        }

        Position withFailure(String why) {
            return new Position(index, answered, failed + 1, refused, remember(why));
        }

        Position withRefusal(String why) {
            return new Position(index, answered, failed, refused + 1, remember(why));
        }

        private String remember(String why) {
            String clean = why == null ? "" : why.replace(';', ',');
            if (clean.length() > 300) {
                clean = clean.substring(0, 300);
            }
            return reason.isEmpty() ? clean : reason + " | " + clean;
        }
    }
}
