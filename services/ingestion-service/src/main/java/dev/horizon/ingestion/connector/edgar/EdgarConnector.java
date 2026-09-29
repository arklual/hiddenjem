package dev.horizon.ingestion.connector.edgar;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.edgar.model.EdgarSearchResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
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
 * SEC EDGAR — подачи Form D и S-1, в тексте которых упомянута технология.
 *
 * <p><b>Зачем.</b> Жюри указало: эксперты исключали из трендов технологии, в которые уже вложены
 * большие деньги, — значит, система должна видеть деньги. Form D — уведомление о частном
 * размещении по Regulation D: у компании состоялся раунд финансирования (ранние деньги). S-1 —
 * регистрация публичного размещения, обычно перед IPO: технология дозрела до биржи. Обе формы
 * подаются в SEC по закону, с датой и названием эмитента, — это не пересказ прессы, а первичный
 * официальный документ.
 *
 * <p><b>Класс — новость.</b> Отдельного класса «регуляторная подача» в модели нет, а по смыслу
 * подача ближе всего к новости о рынке: событие компании с датой. Научной публикацией, патентом или
 * стандартом она не является. Доверенность при этом не страдает: хост {@code sec.gov} — в зоне
 * {@code .gov}, и правила аналитики оценивают его как официальный источник.
 *
 * <p><b>Как спрашивается.</b> Полнотекстовый поиск EDGAR ({@code efts.sec.gov/LATEST/search-index})
 * — тот, что стоит за поиском на sec.gov. Каждая формулировка — отдельный запрос в кавычках (поиск
 * фразы), окно дат передаётся всегда: без {@code dateRange=custom} сервис отвечает 500. Страница —
 * сто файлов, сдвиг — {@code from}; курсор — «номер формулировки:сдвиг». Глубже трёх страниц на
 * формулировку не идём: ответ отсортирован по релевантности, дальше — упоминания мимоходом.
 *
 * <p><b>Файлы, а не подачи.</b> Поиск возвращает каждый файл подачи отдельно: основной документ,
 * согласие аудитора, заключение юриста. Берётся только основной документ (тип файла совпадает с
 * формой), по одному на подачу: приложение без упоминания в основном тексте — слабый сигнал, а
 * повтор той же подачи — не новый сигнал.
 *
 * <p><b>Вежливость.</b> SEC требует описательный {@code User-Agent} с адресом для связи и не более
 * десяти запросов в секунду; мы делаем несколько в минуту. {@code /cgi-bin/browse-edgar} закрыт
 * {@code robots.txt} — туда не ходим; адреса документов ведут в разрешённый {@code /Archives/}.
 *
 * <p><b>Ноль и отказ различаются.</b> Ответ без попаданий — законный ноль. Ошибка сети, 5xx после
 * повторов или тело не по контракту роняют прогон источника, и EDGAR попадает в недоступные.
 */
public class EdgarConnector extends AbstractSourceConnector<EdgarSearchResponse.Raw> {

    public static final String SOURCE_ID = "edgar";
    private static final String DEFAULT_BASE_URL = "https://efts.sec.gov/LATEST/search-index";
    private static final String FORMS = "D,S-1";
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Размер страницы EFTS фиксирован сервером — сто файлов. */
    private static final int PAGE_SIZE = 100;

    private static final int MAX_PAGES_PER_TERM = 3;

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final EdgarNormalizer normalizer = new EdgarNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final String userAgent;

    public EdgarConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        // Политика SEC — не больше десяти запросов в секунду; нам хватает нескольких в минуту,
        // и потолок держится даже при ошибке в конфигурации.
        this.requestsPerMinute = Math.min(settings.requestsPerMinuteOr(6), 60);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "SEC EDGAR (раунды и IPO)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор edgar выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<EdgarSearchResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<EdgarSearchResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
        if (phrases.isEmpty()) {
            return new SourcePage<>(List.of(), Cursor.ofValue("0:0"), true);
        }
        int[] position = positionOf(cursor);
        int term = Math.min(position[0], phrases.size() - 1);
        int from = position[1];
        String phrase = phrases.get(term);

        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("q", "\"" + phrase + "\"")
                .queryParam("dateRange", "custom")
                .queryParam("startdt", request.windowFrom().toString())
                .queryParam("enddt", request.windowTo().toString())
                .queryParam("forms", FORMS)
                .queryParam("from", from)
                .build()
                .encode()
                .toUri();
        RawHttpResponse response = http.get(
                SOURCE_ID,
                uri,
                Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE, HttpHeaders.USER_AGENT, userAgent),
                requestsPerMinute);
        EdgarSearchResponse body =
                JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), EdgarSearchResponse.class);

        List<EdgarSearchResponse.Raw> items = new ArrayList<>();
        Set<String> filings = new HashSet<>();
        for (EdgarSearchResponse.Hit hit : body.hits().hits()) {
            EdgarSearchResponse.Filing filing = hit.source();
            if (filing == null || !isPrimaryDocument(filing) || !inWindow(request, filing)) {
                continue;
            }
            if (filing.adsh() != null && filings.add(filing.adsh())) {
                items.add(new EdgarSearchResponse.Raw(hit, phrase, SOURCE_ID, filing.adsh(), response.provenance()));
            }
        }
        int returned = body.hits().hits().size();
        boolean termDone = returned == 0
                || from + returned >= body.hits().totalValue()
                || from / PAGE_SIZE + 1 >= MAX_PAGES_PER_TERM;
        boolean last = termDone && term + 1 >= phrases.size();
        String next = termDone ? (term + 1) + ":0" : term + ":" + (from + returned);
        return new SourcePage<>(items, Cursor.ofValue(next), last);
    }

    /**
     * Основной документ подачи: тип файла совпадает с формой, а не «EX-23.1» и прочие приложения.
     *
     * <p>Поправки ({@code S-1/A}, {@code D/A}) не берутся: это та же подача той же компании, и живой
     * запрос «quantum computing» давал 180 поправок на 86 первичных S-1 — объём темы рос бы от
     * переписки компании с регулятором, а не от числа компаний.
     */
    private static boolean isPrimaryDocument(EdgarSearchResponse.Filing filing) {
        return filing.form() != null
                && !filing.form().endsWith("/A")
                && filing.form().equalsIgnoreCase(filing.fileType());
    }

    /**
     * Окно сервер уже применил; проверка повторяется, чтобы не зависеть от его трактовки границ. Карточка
     * без читаемой даты пропускается дальше — нормализатор её отвергнет, и потеря попадёт в счётчик
     * отвергнутых, а не исчезнет молча.
     */
    private static boolean inWindow(CollectionRequest request, EdgarSearchResponse.Filing filing) {
        if (filing.fileDate() == null) {
            return true;
        }
        try {
            return request.withinWindow(LocalDate.parse(filing.fileDate().trim()));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static int[] positionOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null || !cursor.value().contains(":")) {
            return new int[] {0, 0};
        }
        String[] parts = cursor.value().split(":", 2);
        try {
            return new int[] {Math.max(0, Integer.parseInt(parts[0])), Math.max(0, Integer.parseInt(parts[1]))};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }
}
