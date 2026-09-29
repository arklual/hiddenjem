package dev.horizon.ingestion.connector.industry;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriUtils;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.industry.model.IndustryArticle;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.TextNormalization;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Профессиональные отраслевые медиа — по изданию на направление, через их поисковые ленты.
 *
 * <p>ТЗ относит «профессиональные отраслевые медиа» к доверенным источникам наравне с научными
 * публикациями. Заведены ради рыночной части эталона: раунды, выходы продуктов, первые внедрения
 * (разбор 90: «заметная часть эталона — события рынка, а не статьи»). Отраслевые ленты уже были —
 * у коннектора {@code rss}, — но лента площадки это последние тридцать записей недели: по запросу
 * направления она приносила 6–12 документов.
 *
 * <p><b>Поисковая лента, а не лента новостей.</b> Все выбранные издания работают на WordPress, и у
 * него есть лента результатов поиска: {@code /search/<запрос>/feed/rss2/?paged=N} — с датой,
 * автором, анонсом и полным текстом, постранично и на годы назад. Список изданий — шаблоны адресов
 * в настройке {@code feeds} с подстановками {@code {q}} и {@code {page}}; по умолчанию —
 * EdgeIR (Edge), SiliconANGLE (инфраструктура ИИ), Plant Engineering (индустриальный ИИ), Robohub
 * (роботы), CyberScoop (защита ИИ), The Fintech Times (финтех). Каждый проверен: поиск
 * отвечает, {@code robots.txt} не запрещает ни поиск, ни ленты, ИИ-краулеров не называет
 * (разбор 101, там же — кто отпал и почему).
 *
 * <p><b>Порядок обхода.</b> Позиция курсора — номер в переборе «страница → формулировка →
 * издание», издание меняется быстрее всего: соседние запросы идут к разным хостам, и пауза в
 * десять секунд на хост почти не удлиняет сбор.
 *
 * <p><b>Ноль и отказ различаются.</b> Издание, которое отказало или ответило не лентой (проверка
 * на робота), выбывает до конца прогона, и это записывается в журнал. Если не ответило ни одно —
 * прогон источника падает, и он попадает в перечень недоступных: иначе отказ всех шести изданий
 * выглядел бы как «новостей нет».
 */
public class IndustryMediaConnector extends AbstractSourceConnector<IndustryArticle> {

    public static final String SOURCE_ID = "industry";
    private static final Logger log = LoggerFactory.getLogger(IndustryMediaConnector.class);
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Две страницы на формулировку: дальше поиск WordPress уходит в слабые совпадения. */
    static final int MAX_PAGES = 2;

    private final ConnectorHttpClient http;
    private final IndustryMediaNormalizer normalizer = new IndustryMediaNormalizer();
    private final List<String> templates;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    /** Самый строгий {@code Crawl-delay} среди изданий (Plant Engineering) — для всех. */
    private final CrawlDelay crawlDelay;

    public IndustryMediaConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(10)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанных лент не ждал десять секунд. */
    IndustryMediaConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.crawlDelay = crawlDelay;
        this.http = http;
        this.templates = settings.feeds().stream()
                .map(String::trim)
                .filter(template -> template.contains("{q}"))
                .toList();
        this.requestsPerMinute = settings.requestsPerMinuteOr(6);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        this.robots = new RobotsPolicy(uri -> {
            try {
                crawlDelay.await(uri.getHost());
                return http.get(keyOf(uri), uri, Map.of(), requestsPerMinute).body();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Отраслевые медиа",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Коннектор industry выключен конфигурацией");
        }
        if (templates.isEmpty()) {
            return descriptor.unavailable(
                    "Не настроено ни одной поисковой ленты с {q} (horizon.connectors.sources.industry.feeds)");
        }
        return descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<IndustryArticle> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<IndustryArticle> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
        if (phrases.isEmpty() || templates.isEmpty()) {
            return SourcePage.empty();
        }
        Position position = Position.of(cursor);
        int sites = templates.size();
        int combos = phrases.size() * sites;
        int total = combos * MAX_PAGES;
        // Пропустить исчерпанные пары и выбывшие издания, не тратя на них запросов.
        while (position.index < total && skip(position, phrases.size(), sites)) {
            position = position.advance();
        }
        if (position.index >= total) {
            return finish(position);
        }
        int page = position.index / combos + 1;
        int phrase = (position.index % combos) / sites;
        int site = position.index % sites;
        URI uri = uriOf(templates.get(site), phrases.get(phrase), page);

        List<IndustryArticle> items = new ArrayList<>();
        Position next;
        if (!robots.allows(uri, userAgent)) {
            log.info("Industry feed {} is disallowed by robots.txt, the outlet is dropped for this run", uri.getHost());
            next = position.dropSite(site).withRefusal().advance();
        } else {
            try {
                crawlDelay.await(uri.getHost());
                RawHttpResponse response = http.get(
                        keyOf(uri),
                        uri,
                        Map.of(HttpHeaders.ACCEPT, "application/rss+xml, application/xml;q=0.9, */*;q=0.8"),
                        requestsPerMinute);
                SearchFeeds.Feed feed = SearchFeeds.parse(SOURCE_ID, uri.toString(), response.body());
                // Происхождение — от имени источника, а не ключа хоста: документ принадлежит
                // источнику `industry`, ключ хоста нужен только ограничителю и предохранителю.
                Provenance provenance = new Provenance(
                        SOURCE_ID,
                        response.fetchedAt(),
                        response.requestUrl(),
                        response.status(),
                        response.payloadHash(),
                        response.rawRef());
                // Издание называется хостом, а не заголовком ленты: у поисковой ленты он свой на
                // каждый запрос («Search Results for “robotics” – Robohub»), и одно издание
                // становилось тридцатью пятью организациями — правило «две независимые
                // организации» проходило на заметках одной редакции.
                String outlet = outletOf(uri);
                for (SearchFeeds.Entry entry : feed.entries()) {
                    if (accepts(entry, request, phrases.get(phrase))) {
                        items.add(new IndustryArticle(entry, outlet, SOURCE_ID, entry.externalId(), provenance));
                    }
                }
                next = position.withAnswer();
                if (feed.entries().isEmpty()) {
                    next = next.exhaust(phrase * sites + site);
                }
                next = next.advance();
            } catch (RuntimeException e) {
                // Не только отказы HTTP: открытый предохранитель хоста бросает своё исключение, и
                // оно не должно ронять прогон по остальным изданиям.
                if (e instanceof ConnectorException connectorException && connectorException.httpStatus() == 404) {
                    // WordPress отвечает 404 на страницу за последней страницей результатов: это
                    // конец выдачи по формулировке, а не отказ издания.
                    next = position.withAnswer().exhaust(phrase * sites + site).advance();
                } else {
                    // Одно издание не стоит остальных пяти, но и молчать о нём нельзя.
                    log.warn(
                            "Industry outlet {} failed and is dropped for this run: {}", uri.getHost(), e.getMessage());
                    next = position.dropSite(site).withFailure().advance();
                }
            }
        }
        if (next.index >= total) {
            SourcePage<IndustryArticle> last = finish(next);
            return new SourcePage<>(items, last.next(), true);
        }
        return new SourcePage<>(items, next.toCursor(), false);
    }

    /**
     * Запись подходит, если она в окне и называет все слова формулировки.
     *
     * <p>Поиск WordPress ищет слова порознь и по всему тексту, включая подписи и меню: SiliconANGLE
     * на «confidential computing» отдаёт и статьи о квантовых вычислениях. Проверка всех слов в
     * заголовке, анонсе и тексте отсекает такие совпадения, не требуя фразы дословно.
     */
    private static boolean accepts(SearchFeeds.Entry entry, CollectionRequest request, String phrase) {
        if (entry.publishedAt() == null || entry.link() == null || entry.title() == null) {
            return false;
        }
        if (!request.withinWindow(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))) {
            return false;
        }
        String text = TextNormalization.normalizeTitle(entry.title() + " "
                + (entry.description() == null ? "" : entry.description()) + " "
                + (entry.content() == null ? "" : entry.content()));
        for (String word : TextNormalization.normalizeTitle(phrase).split(" ")) {
            if (word.length() >= MIN_PHRASE_LENGTH && !text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Последняя позиция перебора: если не ответило ни одно издание, это отказ, а не ноль. */
    private SourcePage<IndustryArticle> finish(Position position) {
        if (position.answered == 0 && (position.failed > 0 || position.refused > 0)) {
            throw new ConnectorException.Retryable(
                    SOURCE_ID,
                    0,
                    "Ни одно отраслевое издание не ответило: отказов %d, запретов robots.txt %d"
                            .formatted(position.failed, position.refused));
        }
        if (position.failed > 0 || position.refused > 0) {
            log.warn(
                    "Industry media answered {} requests; {} outlets failed, {} refused by robots.txt",
                    position.answered,
                    position.failed,
                    position.refused);
        }
        return new SourcePage<>(List.of(), position.toCursor(), true);
    }

    private static boolean skip(Position position, int phrases, int sites) {
        int combos = phrases * sites;
        int phrase = (position.index % combos) / sites;
        int site = position.index % sites;
        return position.dead.testBit(site) || position.exhausted.testBit(phrase * sites + site);
    }

    static URI uriOf(String template, String phrase, int page) {
        String query = UriUtils.encodePathSegment(phrase.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
        return URI.create(template.replace("{q}", query).replace("{page}", Integer.toString(page)));
    }

    static String outletOf(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** Ограничитель и предохранитель — на хост: отказавшее издание не должно тормозить остальные. */
    private static String keyOf(URI uri) {
        return SOURCE_ID + ":" + uri.getHost();
    }

    /**
     * Позиция перебора и то, что о нём уже известно: исчерпанные пары «формулировка × издание»,
     * выбывшие издания и счёт ответов, отказов и запретов. Всё в курсоре, потому что экземпляр
     * коннектора один на все прогоны.
     */
    record Position(int index, BigInteger exhausted, BigInteger dead, int answered, int failed, int refused) {

        static Position of(Cursor cursor) {
            if (cursor == null || cursor.value() == null || cursor.value().isBlank()) {
                return new Position(0, BigInteger.ZERO, BigInteger.ZERO, 0, 0, 0);
            }
            try {
                String[] parts = cursor.value().split(";");
                return new Position(
                        Integer.parseInt(parts[0]),
                        new BigInteger(parts[1], 16),
                        new BigInteger(parts[2], 16),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]));
            } catch (RuntimeException e) {
                return new Position(0, BigInteger.ZERO, BigInteger.ZERO, 0, 0, 0);
            }
        }

        Cursor toCursor() {
            return Cursor.ofValue("%d;%s;%s;%d;%d;%d"
                    .formatted(index, exhausted.toString(16), dead.toString(16), answered, failed, refused));
        }

        Position advance() {
            return new Position(index + 1, exhausted, dead, answered, failed, refused);
        }

        Position exhaust(int pair) {
            return new Position(index, exhausted.setBit(pair), dead, answered, failed, refused);
        }

        Position dropSite(int site) {
            return new Position(index, exhausted, dead.setBit(site), answered, failed, refused);
        }

        Position withAnswer() {
            return new Position(index, exhausted, dead, answered + 1, failed, refused);
        }

        Position withFailure() {
            return new Position(index, exhausted, dead, answered, failed + 1, refused);
        }

        Position withRefusal() {
            return new Position(index, exhausted, dead, answered, failed, refused + 1);
        }
    }
}
