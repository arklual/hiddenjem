package dev.horizon.ingestion.connector.newswire;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.newswire.model.PressRelease;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * PR Newswire — пресс-релизы компаний через страницу поиска новостей.
 *
 * <p>Заведён вместе с GlobeNewswire и по той же причине: рыночная стадия эталона — это объявления
 * компаний, а не статьи. Две площадки не дублируют друг друга: компания обычно выбирает одну, и
 * в выдачах по «stablecoin payments» 2026-09-28 (33 компании PR Newswire на двух страницах, 7 —
 * в ленте GlobeNewswire) общих компаний нет ни одной.
 *
 * <p><b>Спрашивается {@code /search/news/?keyword=…&page=N&pagesize=25}</b> — серверная страница
 * результатов, новые сверху, с датой, компанией и фрагментом текста вокруг совпадения. Ленты по
 * ключевому слову у PR Newswire нет. Страниц — не больше трёх на формулировку (75 релизов), и
 * обход формулировки кончается раньше, если страница неполная или её самый старый релиз уже вне
 * окна: дальше выдача только старше.
 *
 * <p><b>Что можно и чего нельзя.</b> {@code robots.txt} PR Newswire закрывает шаблоны и
 * {@code /multivu/}, а {@code /search/news/} не закрывает; ИИ-краулеров не называет;
 * {@code Crawl-delay} нет. Страница весит четверть мегабайта, поэтому пауза — пять секунд на хост.
 * Проверка — перед обращением; запрет — отказ источника с причиной, а не пустой корпус.
 *
 * <p><b>Ноль и отказ различаются</b> — см. {@link PrNewswireResults}: пометка «ничего не найдено»
 * даёт ноль, страница без карточек и без пометки — отказ.
 */
public class PrNewswireConnector extends AbstractSourceConnector<PressRelease> {

    public static final String SOURCE_ID = "prnewswire";
    static final String WIRE = "PR Newswire";
    private static final Logger log = LoggerFactory.getLogger(PrNewswireConnector.class);
    private static final String DEFAULT_BASE_URL = "https://www.prnewswire.com/search/news/";
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Три страницы по двадцать пять: дальше полнотекстовый поиск уходит в упоминания вскользь. */
    static final int MAX_PAGES = 3;

    private static final int PAGE_SIZE = 25;

    private final ConnectorHttpClient http;
    private final PressReleaseNormalizer normalizer = new PressReleaseNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    private final CrawlDelay crawlDelay;

    public PrNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(5)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанных страниц не ждал. */
    PrNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.crawlDelay = crawlDelay;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.requestsPerMinute = settings.requestsPerMinuteOr(12);
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
                "PR Newswire (пресс-релизы)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор prnewswire выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<PressRelease> normalizer() {
        return normalizer;
    }

    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
    }

    URI searchUri(String phrase, int page) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("keyword", phrase)
                .queryParam("page", page)
                .queryParam("pagesize", PAGE_SIZE)
                .build()
                .encode()
                .toUri();
    }

    /** Курсор — «номер формулировки:номер страницы», страницы с единицы. */
    @Override
    protected SourcePage<PressRelease> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        int[] position = positionOf(cursor);
        int index = position[0];
        int page = position[1];
        if (index >= phrases.size()) {
            return SourcePage.empty();
        }
        URI uri = searchUri(phrases.get(index), page);
        if (!robots.allows(uri, userAgent)) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID, 0, "robots.txt PR Newswire запрещает " + uri.getRawPath());
        }
        crawlDelay.await(uri.getHost());
        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml"), requestsPerMinute);
        PrNewswireResults.Page results = PrNewswireResults.parse(SOURCE_ID, uri, response.body());
        List<PressRelease> items = new ArrayList<>();
        int undated = 0;
        boolean reachedOlder = false;
        for (PrNewswireResults.Card card : results.cards()) {
            if (card.publishedOn() == null) {
                undated++;
                continue;
            }
            if (card.publishedOn().isBefore(request.windowFrom())) {
                reachedOlder = true;
            }
            if (!request.withinWindow(card.publishedOn())) {
                continue;
            }
            items.add(new PressRelease(
                    SOURCE_ID,
                    card.externalId(),
                    WIRE,
                    card.title(),
                    card.url(),
                    card.publishedOn(),
                    card.teaser(),
                    card.issuer(),
                    card.language(),
                    response.provenance()));
        }
        if (undated == results.cards().size() && undated > 0) {
            // Карточки есть, а ни одной даты — сменился формат даты, а не «релизов в окне нет».
            throw new ConnectorException.Permanent(
                    SOURCE_ID, 200, "PR Newswire result dates on " + uri + " do not parse");
        }
        if (undated > 0) {
            log.warn(
                    "PR Newswire: {} of {} result cards on {} had no readable date",
                    undated,
                    results.cards().size(),
                    uri);
        }
        boolean phraseDone = results.cards().size() < PAGE_SIZE || reachedOlder || page >= MAX_PAGES;
        int nextIndex = phraseDone ? index + 1 : index;
        int nextPage = phraseDone ? 1 : page + 1;
        boolean last = phraseDone && nextIndex >= phrases.size();
        return new SourcePage<>(items, Cursor.ofValue(nextIndex + ":" + nextPage), last);
    }

    private static int[] positionOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return new int[] {0, 1};
        }
        String[] parts = cursor.value().split(":");
        try {
            int index = Math.max(Integer.parseInt(parts[0]), 0);
            int page = parts.length > 1 ? Math.max(Integer.parseInt(parts[1]), 1) : 1;
            return new int[] {index, page};
        } catch (NumberFormatException e) {
            return new int[] {0, 1};
        }
    }
}
