package dev.horizon.ingestion.connector.producthunt;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.producthunt.model.ProductLaunch;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Product Hunt — запуски продуктов через Atom-ленту площадки.
 *
 * <p>Заведён ради самой ранней рыночной отметки: день, когда продукт на новой технологии вышел к
 * пользователям. Такой отметки нет ни в статьях, ни в релизах — стартап без бюджета на рассылку
 * запускается именно здесь.
 *
 * <p><b>Глубина честно мала.</b> Поиск Product Hunt ({@code /search*}) закрыт в {@code robots.txt}
 * для всех, API требует ключа и соглашения. Открыта только лента {@code /feed}: пятьдесят недавних
 * запусков, отобранных площадкой, — на 2026-09-28 половина из них за последние пять дней, самый
 * старый — двухмесячный. Поэтому:
 * <ul>
 *   <li>общая лента фильтруется здесь же: запуск берётся, если все слова хотя бы одной формулировки
 *       есть в названии или слогане (с точностью до окончания множественного числа);
 *   <li>для каждой формулировки спрашивается лента рубрики {@code /feed?category=<формулировка через
 *       дефис>}: рубрики «fintech», «payments», «crypto», «robots» существуют и отдают пятьдесят
 *       последних запусков рубрики — у узких это месяцы и годы, у «fintech» на 2026-09-28 — с марта
 *       2026. Запуск из рубрики, совпавшей с формулировкой целиком, берётся без проверки слов: это
 *       классификация самой площадки;
 *   <li>несуществующую рубрику Product Hunt не отвергает, а молча отдаёт общую ленту (и даже с
 *       чужим {@code <id>} от прошлого запроса — кэш площадки). Поэтому рубрика признаётся
 *       настоящей, только если её записи не совпадают с записями общей ленты.
 * </ul>
 * Итог по узкой теме — единицы запусков или ноль; это законный ноль, а не отказ.
 *
 * <p><b>Что можно и чего нельзя.</b> {@code robots.txt} закрывает {@code /search*}, {@code /r/*},
 * {@code /@*}/, {@code /my/*}; ленту не закрывает, ИИ-краулеров не называет. Для всех
 * {@code Crawl-delay} не задан (секунда — только для SemrushBot), держим две секунды на хост.
 *
 * <p><b>Ноль и отказ различаются.</b> Общая лента, которую не удалось получить или разобрать,
 * роняет прогон источника; отказ одной рубрики лишь записывается в журнал — общая лента уже есть.
 */
public class ProductHuntConnector extends AbstractSourceConnector<ProductLaunch> {

    public static final String SOURCE_ID = "producthunt";
    private static final Logger log = LoggerFactory.getLogger(ProductHuntConnector.class);
    private static final String DEFAULT_BASE_URL = "https://www.producthunt.com/feed";
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Рубрик за прогон не больше: каждая — запрос, а большинство формулировок рубриками не являются. */
    static final int MAX_CATEGORIES = 6;
    /** Доля записей, совпавших с общей лентой, при которой «рубрика» — это общая лента. */
    private static final double FALLBACK_OVERLAP = 0.9;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");

    private final ConnectorHttpClient http;
    private final ProductHuntNormalizer normalizer = new ProductHuntNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    private final CrawlDelay crawlDelay;

    public ProductHuntConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(2)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанных лент не ждал. */
    ProductHuntConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.crawlDelay = crawlDelay;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.requestsPerMinute = settings.requestsPerMinuteOr(20);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        this.robots = new RobotsPolicy(uri -> {
            try {
                crawlDelay.await(uri.getHost());
                return http.get(SOURCE_ID, uri, Map.of(), requestsPerMinute).body();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Product Hunt (запуски продуктов)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор producthunt выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<ProductLaunch> normalizer() {
        return normalizer;
    }

    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
    }

    /** «Stablecoin payments» → {@code stablecoin-payments}: так Product Hunt пишет рубрики. */
    static String slug(String phrase) {
        String slug = NOT_SLUG.matcher(phrase.toLowerCase(Locale.ROOT)).replaceAll("-");
        return slug.replaceAll("^-+|-+$", "");
    }

    /** Одна страница: общая лента и ленты рубрик, отфильтрованные по формулировкам. */
    @Override
    protected SourcePage<ProductLaunch> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        if (phrases.isEmpty() || (cursor != null && "done".equals(cursor.value()))) {
            return SourcePage.empty();
        }
        List<Set<String>> wanted =
                phrases.stream().map(ProductHuntConnector::words).toList();
        Fetched main = fetch(null);
        Set<String> mainLinks = linksOf(main.feed());

        Map<String, ProductLaunch> launches = new LinkedHashMap<>();
        collect(main, null, false, wanted, request, launches);
        Set<String> categories = new LinkedHashSet<>();
        for (String phrase : phrases) {
            String slug = slug(phrase);
            if (!slug.isEmpty() && categories.size() < MAX_CATEGORIES) {
                categories.add(slug);
            }
        }
        for (String category : categories) {
            Fetched fetched;
            try {
                fetched = fetch(category);
            } catch (ConnectorException e) {
                log.warn("Product Hunt category feed {} failed: {}", category, e.getMessage());
                continue;
            }
            if (isMainFeed(linksOf(fetched.feed()), mainLinks)) {
                // Рубрики нет, площадка отдала общую ленту — её записи уже просмотрены.
                continue;
            }
            collect(fetched, category, true, wanted, request, launches);
        }
        return new SourcePage<>(List.copyOf(launches.values()), Cursor.ofValue("done"), true);
    }

    private record Fetched(SearchFeeds.Feed feed, RawHttpResponse response) {}

    private Fetched fetch(String category) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl);
        if (category != null) {
            builder.queryParam("category", category);
        }
        URI uri = builder.build().encode().toUri();
        if (!robots.allows(uri, userAgent)) {
            throw new ConnectorException.Permanent(SOURCE_ID, 0, "robots.txt Product Hunt запрещает " + uri);
        }
        crawlDelay.await(uri.getHost());
        RawHttpResponse response = http.get(
                SOURCE_ID,
                uri,
                Map.of(HttpHeaders.ACCEPT, "application/atom+xml, application/xml;q=0.9, */*;q=0.8"),
                requestsPerMinute);
        return new Fetched(SearchFeeds.parse(SOURCE_ID, uri.toString(), response.body()), response);
    }

    private static void collect(
            Fetched fetched,
            String category,
            boolean categoryIsReal,
            List<Set<String>> wanted,
            CollectionRequest request,
            Map<String, ProductLaunch> launches) {
        for (SearchFeeds.Entry entry : fetched.feed().entries()) {
            if (entry.publishedAt() == null || entry.link() == null || entry.title() == null) {
                continue;
            }
            if (launches.containsKey(entry.externalId())) {
                continue;
            }
            if (!request.withinWindow(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))) {
                continue;
            }
            // Рубрика спрошена по формулировке целиком, и площадка её знает: запуск в ней отнесён к
            // теме самой площадкой, и слогану не обязательно повторять название темы.
            boolean byCategory = categoryIsReal && category != null;
            if (!byCategory && !matches(entry, wanted)) {
                continue;
            }
            launches.put(
                    entry.externalId(),
                    new ProductLaunch(
                            entry,
                            category,
                            SOURCE_ID,
                            entry.externalId(),
                            fetched.response().provenance()));
        }
    }

    /** Все слова хотя бы одной формулировки есть в названии или слогане. */
    static boolean matches(SearchFeeds.Entry entry, List<Set<String>> wanted) {
        String text = entry.title() + " "
                + ProductHuntNormalizer.tagline(entry.content() != null ? entry.content() : entry.description());
        Set<String> present = words(text);
        return wanted.stream().anyMatch(words -> !words.isEmpty() && present.containsAll(words));
    }

    /** Слова в нижнем регистре, без окончания множественного числа: «payments» и «payment» — одно. */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            words.add(
                    word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")
                            ? word.substring(0, word.length() - 1)
                            : word);
        }
        return words;
    }

    private static Set<String> linksOf(SearchFeeds.Feed feed) {
        Set<String> links = new HashSet<>();
        for (SearchFeeds.Entry entry : feed.entries()) {
            links.add(entry.externalId());
        }
        return links;
    }

    static boolean isMainFeed(Set<String> category, Set<String> main) {
        if (category.isEmpty() || main.isEmpty()) {
            return category.equals(main);
        }
        long shared = category.stream().filter(main::contains).count();
        return shared >= FALLBACK_OVERLAP * Math.min(category.size(), main.size());
    }
}
