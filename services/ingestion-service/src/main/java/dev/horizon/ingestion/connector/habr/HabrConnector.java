package dev.horizon.ingestion.connector.habr;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.habr.model.HabrPost;
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
 * Хабр — русскоязычные технические публикации, в том числе блоги компаний-разработчиков.
 *
 * <p>Заведён ради той части эталона, которой нет в научной литературе: выход продукта, первое
 * внедрение, разбор архитектуры инженерами компании (разбор 101). Такие сигналы в статьях не
 * появляются годами, а на Хабре — в неделю выхода.
 *
 * <p><b>Спрашивается поисковая лента</b> {@code /ru/rss/search/?q=…}: она отдаёт двадцать самых
 * релевантных публикаций с датой, автором и анонсом. Страниц у неё нет — второй страницы Хабр не
 * отдаёт, {@code order=date} игнорирует, — поэтому глубина набирается числом формулировок, а не
 * страниц: запрос направления и каждый узкий запрос расширения спрашиваются отдельно.
 *
 * <p><b>Что можно и чего нельзя.</b> {@code robots.txt} Хабра запрещает всем HTML-поиск
 * ({@code /search/}, {@code /ru/search/}) и не запрещает RSS; правил, называющих ИИ-краулеров,
 * в нём нет. Ходим только в ленту, проверяя это перед каждым прогоном, и не чаще раза в десять
 * секунд — это {@code Crawl-delay} площадки. Внутренний JSON-интерфейс Хабра ({@code /kek/}) отдаёт
 * историю постранично, но он не документирован и не предназначен для внешних клиентов; им не
 * пользуемся.
 *
 * <p><b>Ноль и отказ различаются.</b> Лента, которую не удалось получить или разобрать, роняет
 * прогон источника — и Хабр попадает в перечень недоступных; лента без записей — законный ноль.
 */
public class HabrConnector extends AbstractSourceConnector<HabrPost> {

    public static final String SOURCE_ID = "habr";
    private static final String DEFAULT_BASE_URL = "https://habr.com/ru/rss/search/";
    private static final int MIN_PHRASE_LENGTH = 3;

    private final ConnectorHttpClient http;
    private final HabrNormalizer normalizer = new HabrNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    /** {@code Crawl-delay: 10} из robots.txt Хабра — строго, без всплеска ограничителя частоты. */
    private final CrawlDelay crawlDelay;

    public HabrConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(10)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанной ленты не ждал десять секунд. */
    HabrConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.crawlDelay = crawlDelay;
        this.http = http;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        // Crawl-delay: 10 в robots.txt Хабра — шесть запросов в минуту, и не больше.
        this.requestsPerMinute = Math.min(settings.requestsPerMinuteOr(6), 6);
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
                "Хабр",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор habr выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<HabrPost> normalizer() {
        return normalizer;
    }

    /**
     * Чем спрашивать Хабр: формулировкой аналитика, если она русская, и целями направления.
     *
     * <p>Остальные источники русскую формулировку не понимают и получают вместо неё цели словаря.
     * Хабр понимает именно её — и отдаёт на «периферийные вычисления» другое, чем на
     * «edge computing»: русские публикации пишут то так, то этак, и спросить стоит обоими.
     */
    static List<String> phrases(CollectionRequest request) {
        var phrases = new LinkedHashSet<String>();
        if (SearchFeeds.hasCyrillic(request.query())) {
            phrases.add(request.query().trim());
        }
        phrases.addAll(SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH));
        return List.copyOf(phrases);
    }

    @Override
    protected SourcePage<HabrPost> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        if (phrases.isEmpty()) {
            return SourcePage.empty();
        }
        int index = indexOf(cursor);
        if (index >= phrases.size()) {
            return SourcePage.empty();
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("q", phrases.get(index))
                .queryParam("target_type", "posts")
                .queryParam("order", "relevance")
                .build()
                .encode()
                .toUri();
        if (!robots.allows(uri, userAgent)) {
            // Отказ площадки — не неисправность, но и не ноль: источник выпадает из прогона с
            // причиной, и оператор видит её в перечне недоступных, а не пустой корпус.
            throw new ConnectorException.Permanent(SOURCE_ID, 0, "robots.txt Хабра запрещает " + uri.getPath());
        }
        crawlDelay.await(uri.getHost());
        RawHttpResponse response = http.get(
                SOURCE_ID,
                uri,
                Map.of(HttpHeaders.ACCEPT, "application/rss+xml, application/xml;q=0.9, */*;q=0.8"),
                requestsPerMinute);
        SearchFeeds.Feed feed = SearchFeeds.parse(SOURCE_ID, uri.toString(), response.body());
        List<HabrPost> items = new ArrayList<>();
        for (SearchFeeds.Entry entry : feed.entries()) {
            if (entry.publishedAt() == null || entry.link() == null || entry.title() == null) {
                continue;
            }
            if (!request.withinWindow(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))) {
                continue;
            }
            items.add(new HabrPost(entry, SOURCE_ID, entry.externalId(), response.provenance()));
        }
        boolean last = index + 1 >= phrases.size();
        return new SourcePage<>(items, Cursor.ofValue(Integer.toString(index + 1)), last);
    }

    private static int indexOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor.value()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
