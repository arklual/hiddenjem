package dev.horizon.ingestion.connector.sbir;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.sbir.model.SbirAward;
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
 * SBIR.gov — награды федеральных программ SBIR и STTR малому бизнесу.
 *
 * <p><b>Зачем.</b> Жюри определяет раннюю стадию как «патенты и прототипы есть, продуктов нет».
 * SBIR/STTR финансирует ровно это: Phase I — проверку осуществимости, Phase II — прототип. Награда —
 * почти прямая метка ранней стадии, причём с именем компании, которая этот прототип делает; научные
 * указатели такого сигнала не дают вовсе.
 *
 * <p><b>Как спрашиваем.</b> JSON-интерфейс площадки закрыт ({@code 403}, см. {@link SbirPages}),
 * поэтому поиск {@code /awards?keywords=…} с отбором по годам окна ({@code year[2025]=2025}) — выдача
 * идёт от новых наград к старым по десять на страницу, — и затем страница каждой награды: только на
 * ней есть точная дата начала работ, полная аннотация, руководитель и партнёр STTR. Одна награда —
 * один запрос, поэтому на формулировку берётся не больше {@code page-size} наград (по умолчанию 25):
 * самые свежие, то есть самые ценные для ранней стадии.
 *
 * <p><b>Вежливость.</b> {@code robots.txt} площадки проверяется перед каждым запросом (разбор
 * кэшируется на хост) и закрывает только {@code /search/} и служебные пути Drupal. Паузы
 * {@code Crawl-delay} в нём нет; сайт — государственный, не API, и мы держим три секунды между
 * запросами, без всплеска ограничителя частоты.
 *
 * <p><b>Ноль и отказ различаются.</b> «No results found.» — законный ноль; страница, которая не
 * выдача, роняет прогон источника. Одна недоступная страница награды ({@code 404}) пропускается —
 * терять из-за неё остальные двадцать четыре незачем; сбой площадки после повторов — отказ.
 */
public class SbirConnector extends AbstractSourceConnector<SbirAward> {

    public static final String SOURCE_ID = "sbir";
    private static final Logger log = LoggerFactory.getLogger(SbirConnector.class);
    private static final String DEFAULT_BASE_URL = "https://www.sbir.gov/awards";
    private static final int MIN_PHRASE_LENGTH = 3;
    /** {@code maxlength="50"} поля «Keywords» формы поиска. */
    private static final int MAX_KEYWORDS_LENGTH = 50;
    /** Ранее этого года наград в базе нет — первый год программы SBIR. */
    private static final int FIRST_AWARD_YEAR = 1983;

    private static final int DETAIL_CACHE_SIZE = 2000;

    private final ConnectorHttpClient http;
    private final SbirNormalizer normalizer = new SbirNormalizer();
    private final String baseUrl;
    private final int awardsPerPhrase;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    private final CrawlDelay crawlDelay;
    /**
     * Уже разобранные страницы наград. Формулировки одного направления пересекаются, а направления
     * — между собой; каждая повторная страница стоила бы площадке запроса и нам трёх секунд.
     */
    private final Map<String, SbirAward> awards = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SbirAward> eldest) {
            return size() > DETAIL_CACHE_SIZE;
        }
    };

    public SbirConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(3)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанных страниц не ждал. */
    SbirConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.crawlDelay = crawlDelay;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.awardsPerPhrase = Math.min(settings.pageSizeOr(25), 100);
        // Три секунды между запросами — двадцать в минуту, и не больше.
        this.requestsPerMinute = Math.min(settings.requestsPerMinuteOr(20), 20);
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
                "SBIR/STTR — гранты малому бизнесу (США)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор sbir выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<SbirAward> normalizer() {
        return normalizer;
    }

    /** Формулировки для поля «Keywords»: цели направления без кодов классификатора. */
    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH).stream()
                .map(SbirConnector::fitKeywords)
                .distinct()
                .toList();
    }

    private static String fitKeywords(String phrase) {
        if (phrase.length() <= MAX_KEYWORDS_LENGTH) {
            return phrase;
        }
        int space = phrase.lastIndexOf(' ', MAX_KEYWORDS_LENGTH);
        return phrase.substring(0, space > 0 ? space : MAX_KEYWORDS_LENGTH).trim();
    }

    @Override
    protected SourcePage<SbirAward> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        Position position = Position.of(cursor);
        if (position.phrase() >= phrases.size()) {
            return SourcePage.empty();
        }
        URI uri = listingUri(phrases.get(position.phrase()), request, position.page());
        RawHttpResponse response = get(uri);
        SbirPages.Listing listing = SbirPages.listing(SOURCE_ID, uri.toString(), response.body());

        List<SbirAward> items = new ArrayList<>();
        int taken = position.taken();
        for (SbirPages.Row row : listing.rows()) {
            if (taken >= awardsPerPhrase) {
                break;
            }
            if (row.year() != null
                    && (row.year() < request.windowFrom().getYear()
                            || row.year() > request.windowTo().getYear())) {
                continue;
            }
            taken++;
            SbirAward award = award(row.awardId());
            if (award == null) {
                continue;
            }
            // Страница без даты уходит нормализатору и отбраковывается там — на виду в счётчике.
            if (award.awardedOn() != null && !request.withinWindow(award.awardedOn())) {
                continue;
            }
            items.add(award);
        }
        boolean phraseDone = listing.last() || taken >= awardsPerPhrase;
        Position next = phraseDone
                ? new Position(position.phrase() + 1, 0, 0)
                : new Position(position.phrase(), position.page() + 1, taken);
        boolean last = phraseDone && next.phrase() >= phrases.size();
        return new SourcePage<>(items, next.cursor(), last);
    }

    URI listingUri(String phrase, CollectionRequest request, int page) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl).queryParam("keywords", phrase);
        int from = Math.max(request.windowFrom().getYear(), FIRST_AWARD_YEAR);
        for (int year = request.windowTo().getYear(); year >= from; year--) {
            builder.queryParam("year[" + year + "]", Integer.toString(year));
        }
        if (page > 0) {
            builder.queryParam("page", page);
        }
        return builder.build().encode().toUri();
    }

    private SbirAward award(String awardId) {
        synchronized (awards) {
            SbirAward cached = awards.get(awardId);
            if (cached != null) {
                return cached;
            }
        }
        URI uri = URI.create(awardUrl(awardId));
        RawHttpResponse response;
        try {
            response = get(uri);
        } catch (ConnectorException.Permanent e) {
            log.debug("SBIR award {} skipped: {}", awardId, e.getMessage());
            return null;
        }
        SbirAward award = SbirPages.award(awardId, uri.toString(), response.body(), SOURCE_ID, response.provenance());
        synchronized (awards) {
            awards.put(awardId, award);
        }
        return award;
    }

    /** Постоянный адрес награды — от корня площадки, а не от настроенного адреса поиска. */
    private String awardUrl(String awardId) {
        URI base = URI.create(baseUrl);
        return base.getScheme() + "://" + base.getAuthority() + "/awards/" + awardId;
    }

    private RawHttpResponse get(URI uri) {
        if (!robots.allows(uri, userAgent)) {
            throw new ConnectorException.Permanent(SOURCE_ID, 0, "robots.txt SBIR.gov запрещает " + uri.getPath());
        }
        crawlDelay.await(uri.getHost());
        return http.get(SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, "text/html"), requestsPerMinute);
    }

    /**
     * Где остановился обход: формулировка, страница её выдачи и сколько наград уже взято.
     * Курсор — {@code "формулировка:страница:взято"}.
     */
    record Position(int phrase, int page, int taken) {

        static Position of(Cursor cursor) {
            if (cursor == null || cursor.value() == null) {
                return new Position(0, 0, 0);
            }
            String[] parts = cursor.value().split(":");
            try {
                return new Position(
                        Math.max(Integer.parseInt(parts[0]), 0),
                        parts.length > 1 ? Math.max(Integer.parseInt(parts[1]), 0) : 0,
                        parts.length > 2 ? Math.max(Integer.parseInt(parts[2]), 0) : 0);
            } catch (NumberFormatException e) {
                return new Position(0, 0, 0);
            }
        }

        Cursor cursor() {
            return Cursor.ofValue(phrase + ":" + page + ":" + taken);
        }
    }
}
