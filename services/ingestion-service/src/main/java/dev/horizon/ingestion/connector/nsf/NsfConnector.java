package dev.horizon.ingestion.connector.nsf;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.nsf.model.NsfResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.JsonBodies;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * NSF Award Search API — награды Национального научного фонда США.
 *
 * <p><b>Зачем.</b> Грант на исследование — сигнал раньше статьи: деньги выделяют под работу,
 * результатов которой ещё нет. А программы SBIR/STTR и I-Corps того же фонда финансируют прототип и
 * проверку рынка — ровно «прототипы есть, продуктов нет», как жюри определяет раннюю стадию.
 *
 * <p><b>Как спрашиваем.</b> Каждая формулировка — отдельным запросом и в кавычках: без кавычек API
 * соединяет слова через ИЛИ и на «neuromorphic computing» отдаёт десять тысяч наград о чём угодно,
 * в кавычках — 190 (замер 2026-09-28). Окно передаётся {@code dateStart}/{@code dateEnd} — по дате
 * решения о награде. По 25 записей на страницу (больше API не отдаёт), не больше четырёх страниц
 * на формулировку: выдача идёт от поздних наград к ранним, и сотня самых свежих — то, что нужно для
 * ранней стадии. Порядок внутри одной даты начала у API неустойчив, и соседние страницы могут
 * повторить или пропустить запись: повторы снимает дедупликация корпуса, пропуск на краю сотни
 * наград не меняет картины.
 *
 * <p><b>Параметр с ошибкой API молча игнорирует</b> — дату не в формате {@code MM/dd/yyyy} он
 * пропускает и отдаёт всю историю. Поэтому окно проверяется ещё и здесь, по каждой записи: иначе
 * опечатка в формате превратилась бы в корпус из наград двадцатилетней давности.
 *
 * <p><b>Ноль и отказ различаются.</b> {@code totalCount: 0} — законный ноль; ответ без тела
 * {@code response} или с {@code serviceNotification} типа ошибки — отказ источника.
 */
public class NsfConnector extends AbstractSourceConnector<NsfResponse.Raw> {

    public static final String SOURCE_ID = "nsf";
    private static final String DEFAULT_BASE_URL = "https://api.nsf.gov/services/v1/awards.json";
    private static final int MIN_PHRASE_LENGTH = 3;
    private static final int MAX_PAGE_SIZE = 25;
    private static final int MAX_PAGES_PER_PHRASE = 4;
    private static final DateTimeFormatter NSF_DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US);
    private static final String PRINT_FIELDS = String.join(
            ",",
            "id",
            "title",
            "startDate",
            "date",
            "awardeeName",
            "awardeeCountryCode",
            "abstractText",
            "fundProgramName",
            "piFirstName",
            "piLastName");

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final NsfNormalizer normalizer = new NsfNormalizer();
    private final String baseUrl;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public NsfConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.pageSize = Math.min(settings.pageSizeOr(MAX_PAGE_SIZE), MAX_PAGE_SIZE);
        // Лимита API не публикует; тридцать в минуту — вежливо для государственного сервиса и
        // достаточно: формулировка стоит не больше четырёх запросов.
        this.requestsPerMinute = settings.requestsPerMinuteOr(30);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "NSF — гранты Национального научного фонда США",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор nsf выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<NsfResponse.Raw> normalizer() {
        return normalizer;
    }

    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
    }

    /** Фраза из нескольких слов — в кавычках, иначе API ищет любое из слов. */
    static String keyword(String phrase) {
        return phrase.contains(" ") ? "\"" + phrase + "\"" : phrase;
    }

    @Override
    protected SourcePage<NsfResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        int[] position = position(cursor);
        int phrase = position[0];
        int page = position[1];
        if (phrase >= phrases.size()) {
            return SourcePage.empty();
        }
        URI uri = uri(phrases.get(phrase), request, page);
        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE), requestsPerMinute);
        NsfResponse body = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), NsfResponse.class);
        if (body.response() == null) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "NSF returned no response body for " + uri);
        }
        for (NsfResponse.Notification notification : body.response().serviceNotification()) {
            if ("ERROR".equalsIgnoreCase(notification.notificationType())) {
                throw new ConnectorException.Permanent(
                        SOURCE_ID, 200, "NSF rejected %s: %s".formatted(uri, notification.notificationMessage()));
            }
        }

        List<NsfResponse.Award> awards = body.response().award();
        List<NsfResponse.Raw> items = new ArrayList<>(awards.size());
        for (NsfResponse.Award award : awards) {
            if (award.id() == null || award.id().isBlank()) {
                continue;
            }
            if (!request.withinWindow(NsfNormalizer.awardedOn(award))) {
                continue;
            }
            items.add(new NsfResponse.Raw(award, SOURCE_ID, award.id(), response.provenance()));
        }

        Integer total = body.response().metadata() == null
                ? null
                : body.response().metadata().totalCount();
        int seen = (page + 1) * pageSize;
        boolean phraseDone =
                awards.size() < pageSize || (total != null && seen >= total) || page + 1 >= MAX_PAGES_PER_PHRASE;
        int nextPhrase = phraseDone ? phrase + 1 : phrase;
        int nextPage = phraseDone ? 0 : page + 1;
        boolean last = phraseDone && nextPhrase >= phrases.size();
        return new SourcePage<>(items, Cursor.ofValue(nextPhrase + ":" + nextPage), last);
    }

    URI uri(String phrase, CollectionRequest request, int page) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("keyword", keyword(phrase))
                .queryParam("dateStart", NSF_DATE.format(request.windowFrom()))
                .queryParam("dateEnd", NSF_DATE.format(request.windowTo()))
                .queryParam("printFields", PRINT_FIELDS)
                .queryParam("rpp", pageSize)
                // Смещение с нуля: offset=1 теряет первую запись выдачи (проверено 2026-09-28).
                .queryParam("offset", page * pageSize)
                .build()
                .encode()
                .toUri();
    }

    private static int[] position(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return new int[] {0, 0};
        }
        String[] parts = cursor.value().split(":");
        try {
            return new int[] {
                Math.max(Integer.parseInt(parts[0]), 0), parts.length > 1 ? Math.max(Integer.parseInt(parts[1]), 0) : 0
            };
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }
}
