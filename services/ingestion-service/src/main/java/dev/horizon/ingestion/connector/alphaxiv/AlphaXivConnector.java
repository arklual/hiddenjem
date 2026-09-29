package dev.horizon.ingestion.connector.alphaxiv;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.RawDocument;
import dev.horizon.ingestion.domain.port.RawPayloadStore;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Discovers papers with alphaXiv MCP, then reads each selected paper's extracted original text.
 * The discovery ranking selects documents; its generated preview never becomes source evidence.
 * Canonical arXiv IDs let the corpus deduplicate papers already found by the arXiv connector.
 */
public class AlphaXivConnector extends AbstractSourceConnector<AlphaXivConnector.Paper> {

    public static final String SOURCE_ID = "alphaxiv";
    private static final Logger log = LoggerFactory.getLogger(AlphaXivConnector.class);
    private static final String DONE = "done";
    private static final Pattern RESULT = Pattern.compile("^\\d+\\. \\[ID=([0-9]{4}\\.[0-9]{4,5})] \\*\\*(.*?)\\*\\* "
            + "\\((https://(?:www\\.)?alphaxiv\\.org/abs/[^)]+)\\)\\. Published "
            + "(\\d{4}-\\d{2}-\\d{2})(?: .*|$)");

    private final AlphaXivClient client;
    private final ObjectMapper mapper;
    private final RawPayloadStore rawStore;
    private final Clock clock;
    private final String endpoint;
    private final boolean enabled;
    private final boolean hasKey;
    private final int maxPapers;
    private final int requestsPerMinute;

    public AlphaXivConnector(
            ConnectorsProperties properties,
            AlphaXivClient client,
            ObjectMapper mapper,
            RawPayloadStore rawStore,
            Clock clock) {
        var settings = properties.settings(SOURCE_ID);
        this.client = client;
        this.mapper = mapper;
        this.rawStore = rawStore;
        this.clock = clock;
        this.endpoint = settings.baseUrlOr("https://api.alphaxiv.org/mcp/v1");
        this.enabled = settings.enabledOr(false);
        this.hasKey = settings.hasApiKey();
        this.maxPapers = Math.min(settings.pageSizeOr(15), 15);
        this.requestsPerMinute = settings.requestsPerMinuteOr(30);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "alphaXiv",
                SourceClass.PREPRINT,
                Set.of(SourceClass.PREPRINT),
                requestsPerMinute,
                true,
                hasKey,
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Источник alphaXiv выключен (HORIZON_SOURCE_ALPHAXIV_ENABLED)");
        }
        return hasKey ? descriptor : descriptor.unavailable("Не задан HORIZON_ALPHAXIV_API_KEY");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.accepts(SourceClass.PREPRINT);
    }

    @Override
    protected DocumentNormalizer<Paper> normalizer() {
        return paper -> Document.builder()
                .externalRef(SOURCE_ID, paper.externalId())
                .sourceClass(SourceClass.PREPRINT)
                .title(paper.title())
                .abstractText(paper.excerpt())
                .language("en")
                .publishedOn(paper.publishedOn())
                .arxivId(paper.externalId())
                .url(paper.url())
                .venue(new Venue("arXiv", "PREPRINT_SERVER", null))
                .provenance(paper.provenance())
                .build();
    }

    @Override
    protected SourcePage<Paper> fetchPage(CollectionRequest request, Cursor cursor) {
        if (cursor != null && DONE.equals(cursor.value())) {
            return SourcePage.empty();
        }
        String search = client.call("discover_papers", discoverArguments(request));
        String listing = toolText(search);
        String searchRef = archive(search);
        List<Paper> papers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : listing.lines().toList()) {
            var result = RESULT.matcher(line);
            if (!result.matches() || !seen.add(result.group(1))) {
                continue;
            }
            LocalDate date = LocalDate.parse(result.group(4));
            if (!request.withinWindow(date)) {
                continue;
            }
            if (papers.size() >= maxPapers) {
                break;
            }
            String id = result.group(1);
            String url = result.group(3);
            try {
                String raw = client.call("get_paper_content", paperArguments(url));
                String content = toolText(raw);
                if (content.isBlank()) {
                    continue;
                }
                String rawRef = archive(raw);
                byte[] payload = raw.getBytes(StandardCharsets.UTF_8);
                Provenance provenance =
                        new Provenance(SOURCE_ID, clock.instant(), endpoint, 200, Hashing.sha256Hex(payload), rawRef);
                papers.add(new Paper(id, url, result.group(2), date, excerpt(content), provenance));
            } catch (ConnectorException e) {
                log.warn("alphaXiv не прочитал статью {}: {}", id, e.getMessage());
            }
        }
        if (papers.isEmpty() && !seen.isEmpty()) {
            throw new ConnectorException.Retryable(SOURCE_ID, 0, "alphaXiv нашёл статьи, но не прочитал ни одной");
        }
        log.info(
                "alphaXiv для «{}»: найдено {}, прочитано {}, архив поиска {}",
                request.query(),
                seen.size(),
                papers.size(),
                searchRef);
        return SourcePage.last(papers, Cursor.ofValue(DONE));
    }

    /**
     * Вопрос к alphaXiv — по правилам самого инструмента, а не по привычке поисковика.
     *
     * <p>Описание {@code discover_papers} говорит прямо: ключевые слова — только термины
     * спрашивающего, «общие термины, в отличие от обычного поиска, снижают качество»; вопрос —
     * «своими словами, короткий лучше раздутого»; свежесть — через {@code prioritize: recency}, а не
     * через выдуманную границу дат. Прежде сюда добавлялись «emerging technology», «applied
     * research», «new methods» и длинный вопрос о смене подходов — ровно то, что инструмент просит не
     * делать.
     *
     * <p>Поэтому: вопрос — формулировка аналитика или узкий запрос расширения как есть; ключевые
     * слова — термины этого же запроса (для русской формулировки — предметные термины направления,
     * которыми каталог сам себя размечает), не больше трёх и без заполнителей. Нижняя граница —
     * окно анализа: она настоящая, статья старше окна в отчёт не попадёт всё равно.
     */
    private String discoverArguments(CollectionRequest request) {
        List<String> keywords = new ArrayList<>();
        for (String term : request.upstreamTerms()) {
            String trimmed = term.trim();
            if (!trimmed.isEmpty() && !keywords.contains(trimmed) && keywords.size() < 3) {
                keywords.add(trimmed);
            }
        }
        var arguments = mapper.createObjectNode();
        arguments.putPOJO("keywords", keywords);
        arguments.put("question", request.query());
        arguments.put("difficulty", 6);
        arguments.put("prioritize", "recency");
        arguments.put("published_after", request.windowFrom().toString());
        return arguments.toString();
    }

    private String paperArguments(String url) {
        var arguments = mapper.createObjectNode();
        arguments.put("url", url);
        arguments.put("fullText", true);
        return arguments.toString();
    }

    private String toolText(String response) {
        String data = response;
        if (response.startsWith("event:") || response.startsWith("data:")) {
            data = response.lines()
                    .filter(line -> line.startsWith("data: "))
                    .map(line -> line.substring(6))
                    .findFirst()
                    .orElse("");
        }
        try {
            JsonNode root = mapper.readTree(data);
            if (root.has("error") || root.path("result").path("isError").asBoolean()) {
                throw new ConnectorException.Permanent(SOURCE_ID, 200, "alphaXiv MCP вернул ошибку инструмента");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("result").path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(block.path("text").asText());
                }
            }
            return text.toString();
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "Неразборчивый ответ alphaXiv MCP", e);
        }
    }

    private String archive(String response) {
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        Instant now = clock.instant();
        return rawStore.archive(SOURCE_ID, Hashing.sha256Hex(payload), now, payload, "text/event-stream")
                .orElse(null);
    }

    private static String excerpt(String fullText) {
        String firstPage = fullText.split("\\f|(?m)^1\\. Introduction", 2)[0].trim();
        String selected = firstPage.length() < 200 ? fullText : firstPage;
        return selected.substring(0, Math.min(selected.length(), 4000));
    }

    record Paper(
            String externalId, String url, String title, LocalDate publishedOn, String excerpt, Provenance provenance)
            implements RawDocument {
        @Override
        public String sourceId() {
            return SOURCE_ID;
        }
    }
}
