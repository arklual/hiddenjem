package dev.horizon.ingestion.config;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.connector.alphaxiv.AlphaXivClient;
import dev.horizon.ingestion.connector.alphaxiv.AlphaXivConnector;
import dev.horizon.ingestion.connector.arxiv.ArxivConnector;
import dev.horizon.ingestion.connector.crossref.CrossrefConnector;
import dev.horizon.ingestion.connector.deepresearch.DeepResearchClient;
import dev.horizon.ingestion.connector.deepresearch.DeepResearchConnector;
import dev.horizon.ingestion.connector.deepresearch.WebCorpusConnector;
import dev.horizon.ingestion.connector.edgar.EdgarConnector;
import dev.horizon.ingestion.connector.europepmc.EuropePmcConnector;
import dev.horizon.ingestion.connector.gdelt.GdeltConnector;
import dev.horizon.ingestion.connector.github.GitHubConnector;
import dev.horizon.ingestion.connector.habr.HabrConnector;
import dev.horizon.ingestion.connector.hackernews.HackerNewsConnector;
import dev.horizon.ingestion.connector.ietf.IetfConnector;
import dev.horizon.ingestion.connector.industry.IndustryMediaConnector;
import dev.horizon.ingestion.connector.lens.LensPatentConnector;
import dev.horizon.ingestion.connector.lens.LensScholarlyConnector;
import dev.horizon.ingestion.connector.newswire.GlobeNewswireConnector;
import dev.horizon.ingestion.connector.newswire.PrNewswireConnector;
import dev.horizon.ingestion.connector.nsf.NsfConnector;
import dev.horizon.ingestion.connector.openalex.OpenAlexConnector;
import dev.horizon.ingestion.connector.openalex.OpenAlexQuotaService;
import dev.horizon.ingestion.connector.producthunt.ProductHuntConnector;
import dev.horizon.ingestion.connector.regulators.RegulatorsConnector;
import dev.horizon.ingestion.connector.rss.RssConnector;
import dev.horizon.ingestion.connector.sbir.SbirConnector;
import dev.horizon.ingestion.connector.semanticscholar.SemanticScholarConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.FilesystemRawPayloadStore;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.connector.uspto.UsptoConnector;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.RawPayloadStore;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import reactor.netty.http.client.HttpClient;

/**
 * Wires the source connectors and the machinery they share.
 *
 * <p>Connectors are declared as beans here rather than annotated with {@code @Component} on purpose:
 * a connector is a driven adapter whose construction depends on configuration, and keeping the
 * wiring in one file makes the set of active sources reviewable at a glance instead of scattered
 * across seven packages. It also keeps the connector classes free of Spring, so they can be
 * instantiated directly in tests.
 *
 * <p>Every connector reads its own {@code horizon.connectors.sources.<id>} block and reports itself
 * unusable when it is disabled or missing credentials — that decision belongs to the connector, not
 * to this configuration, which is why nothing here is conditional on a per-source flag.
 */
@Configuration
public class ConnectorConfiguration {

    /**
     * Shared HTTP client.
     *
     * <p>Timeouts are set at every layer that can hang: connect, read, and overall response. A
     * connector without a response timeout is the classic way a scheduled ingestion run wedges
     * forever holding a rate-limit permit.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public WebClient connectorWebClient(ConnectorsProperties properties) {
        // Заглушка вместо почтового адреса — не косметика. Crossref и OpenAlex пускают
        // представившихся в «вежливый пул» и режут остальных; с адресом на example.org мы для них
        // безымянный вызывающий. Симптом приходит не сборкой, а HTTP 429 через несколько часов, на
        // машине, за которой никто не смотрит, и прогон сбора молча приносит ноль документов.
        if (properties.contactEmailIsPlaceholder()) {
            LOGGER.warn(
                    "connectors.contact_email_is_placeholder value={} — источники ограничат частоту;"
                            + " задайте HORIZON_CONNECTOR_CONTACT_EMAIL",
                    properties.contactEmail());
        }
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int)
                        properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout())
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(properties.responseTimeout().toSeconds(), TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", properties.fullUserAgent())
                // A source that starts returning gigabytes must fail this run, not the JVM.
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.maxResponseBytes()))
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiters rateLimiters(ConnectorsProperties properties) {
        return new InMemoryRateLimiters(properties.resilience().permittedCallsInHalfOpenState());
    }

    /**
     * Archive of raw source responses (BR-C5).
     *
     * <p>Filesystem by default so provenance works out of the box; the S3 adapter plugs in here.
     * Set {@code horizon.connectors.raw-payload-path} to empty to disable archiving entirely — the
     * no-op store exists so that "we keep raw payloads" can be switched off deliberately rather than
     * by an unset variable silently doing nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "horizon.connectors", name = "raw-payload-path", matchIfMissing = true)
    public RawPayloadStore rawPayloadStore(
            @org.springframework.beans.factory.annotation.Value("${horizon.connectors.raw-payload-path:var/raw}")
                    String rootPath) {
        return rootPath == null || rootPath.isBlank()
                ? new NoopRawPayloadStore()
                : new FilesystemRawPayloadStore(Path.of(rootPath));
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectorHttpClient connectorHttpClient(
            WebClient connectorWebClient,
            RateLimiters rateLimiters,
            RawPayloadStore rawPayloadStore,
            ConnectorsProperties properties,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new ConnectorHttpClient(
                connectorWebClient, rateLimiters, rawPayloadStore, properties, clock, meterRegistry);
    }

    // ── Source connectors ────────────────────────────────────────────────────────────────────
    //
    // Adding a source is one method here plus one row in the `sources` table (BR-C2). Nothing
    // above this configuration changes — the catalogue discovers connectors through the
    // SourceConnector port.

    @Bean
    public ArxivConnector arxivConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new ArxivConnector(properties, http);
    }

    @Bean
    public AlphaXivConnector alphaXivConnector(
            ConnectorsProperties properties, ObjectMapper objectMapper, RawPayloadStore rawPayloadStore, Clock clock) {
        var settings = properties.settings(AlphaXivConnector.SOURCE_ID);
        return new AlphaXivConnector(
                properties,
                AlphaXivClient.http(
                        settings.baseUrlOr("https://api.alphaxiv.org/mcp/v1"),
                        settings.apiKey() == null ? "" : settings.apiKey(),
                        properties.fullUserAgent()),
                objectMapper,
                rawPayloadStore,
                clock);
    }

    @Bean
    public OpenAlexConnector openAlexConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new OpenAlexConnector(properties, http, objectMapper);
    }

    @Bean
    public OpenAlexQuotaService openAlexQuotaService(WebClient connectorWebClient, OpenAlexConnector openAlexConnector) {
        return new OpenAlexQuotaService(connectorWebClient, openAlexConnector);
    }

    // Патенты США без ключа (разбор 110): публичный поиск USPTO вместо PatentsView, ключ которого
    // гражданам РФ не выдают.
    @Bean
    public UsptoConnector usptoConnector(
            ConnectorsProperties properties, ObjectMapper objectMapper, RawPayloadStore rawPayloadStore, Clock clock) {
        return new UsptoConnector(properties, objectMapper, rawPayloadStore, clock);
    }

    @Bean
    public CrossrefConnector crossrefConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new CrossrefConnector(properties, http, objectMapper);
    }

    @Bean
    public GitHubConnector gitHubConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new GitHubConnector(properties, http, objectMapper);
    }

    @Bean
    public RssConnector rssConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new RssConnector(properties, http);
    }

    @Bean
    public LensScholarlyConnector lensScholarlyConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new LensScholarlyConnector(properties, http, objectMapper);
    }

    @Bean
    public LensPatentConnector lensPatentConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new LensPatentConnector(properties, http, objectMapper);
    }

    @Bean
    public SemanticScholarConnector semanticScholarConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new SemanticScholarConnector(properties, http, objectMapper);
    }

    @Bean
    public EuropePmcConnector europePmcConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new EuropePmcConnector(properties, http, objectMapper);
    }

    @Bean
    public HackerNewsConnector hackerNewsConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new HackerNewsConnector(properties, http, objectMapper);
    }

    // Продуктовые и отраслевые источники (разбор 101): русские технические публикации и блоги
    // компаний, профессиональные отраслевые медиа по направлениям датасета.
    @Bean
    public HabrConnector habrConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new HabrConnector(properties, http);
    }

    @Bean
    public IndustryMediaConnector industryMediaConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new IndustryMediaConnector(properties, http);
    }

    // Рыночная стадия (разбор 109): пресс-релизы компаний и запуски продуктов с датой.
    @Bean
    public GlobeNewswireConnector globeNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new GlobeNewswireConnector(properties, http);
    }

    @Bean
    public PrNewswireConnector prNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new PrNewswireConnector(properties, http);
    }

    @Bean
    public ProductHuntConnector productHuntConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new ProductHuntConnector(properties, http);
    }

    // Деньги и стандарты (разбор 109): раунды и IPO из EDGAR, черновики стандартов IETF.
    @Bean
    public EdgarConnector edgarConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new EdgarConnector(properties, http, objectMapper);
    }

    @Bean
    public IetfConnector ietfConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new IetfConnector(properties, http, objectMapper);
    }

    // Ранняя стадия и финтех (разбор 109): гранты на прототипы, программы регуляторов.
    @Bean
    public SbirConnector sbirConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new SbirConnector(properties, http);
    }

    @Bean
    public NsfConnector nsfConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new NsfConnector(properties, http, objectMapper);
    }

    @Bean
    public RegulatorsConnector regulatorsConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        return new RegulatorsConnector(properties, http);
    }

    // Глубокое исследование (разбор 102): агент сервиса моделей ищет по открытым каталогам и читает
    // страницы целиком; в корпус идут прочитанные страницы, подтвердившие названные имена.
    @Bean
    public DeepResearchConnector deepResearchConnector(
            ConnectorsProperties properties,
            DeepResearchProperties research,
            ObjectMapper objectMapper,
            RawPayloadStore rawPayloadStore,
            Clock clock) {
        return new DeepResearchConnector(
                properties,
                research,
                DeepResearchClient.http(research.nlpUrl()),
                objectMapper,
                rawPayloadStore,
                clock);
    }

    // Собственный веб-корпус (разбор 110): страницы, собранные заранее, ищет сервис моделей.
    @Bean
    public WebCorpusConnector webCorpusConnector(
            ConnectorsProperties properties,
            DeepResearchProperties research,
            ObjectMapper objectMapper,
            RawPayloadStore rawPayloadStore,
            Clock clock) {
        return new WebCorpusConnector(
                properties,
                research,
                DeepResearchClient.http(
                        research.nlpUrl(), "/webcorpus/search", WebCorpusConnector.SOURCE_ID, "поиск по веб-корпусу"),
                objectMapper,
                rawPayloadStore,
                clock);
    }

    @Bean
    public GdeltConnector gdeltConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        return new GdeltConnector(properties, http, objectMapper);
    }
}
