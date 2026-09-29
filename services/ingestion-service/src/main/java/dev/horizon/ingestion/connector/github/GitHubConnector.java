package dev.horizon.ingestion.connector.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.github.model.GitHubSearchResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.JsonBodies;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * GitHub repository search — the code-adoption signal.
 *
 * <p><b>Optional token.</b> Unauthenticated search is limited to 10 requests per minute,
 * authenticated to 30; the connector works either way and simply declares the lower budget when no
 * token is configured. Unlike alphaXiv the token is not required, so a missing one does not make
 * the source unavailable — it makes it slower, which is a different and much cheaper problem.
 *
 * <p><b>Paging: page numbers, hard-capped.</b> GitHub's search API refuses to return more than 1000
 * results (10 pages of 100), so the connector stops there instead of walking into a guaranteed 422.
 * Results are sorted by stars so that the 1000 we may see are the 1000 worth seeing.
 */
public class GitHubConnector extends AbstractSourceConnector<GitHubSearchResponse.Raw> {

    public static final String SOURCE_ID = "github";
    private static final String DEFAULT_BASE_URL = "https://api.github.com/search/repositories";
    private static final int MAX_RESULTS = 1000;
    /**
     * Сколько целей направления попадает в запрос. Поиск GitHub отвергает запрос более чем с пятью
     * логическими операторами, а пять целей дают четыре ИЛИ — запас на один оператор внутри самой
     * цели.
     */
    private static final int MAX_TERMS = 5;
    /** Коды предметных словарей вроде {@code cs.LG} и {@code cond-mat.mtrl-sci}. */
    private static final java.util.regex.Pattern CLASSIFICATION_CODE =
            java.util.regex.Pattern.compile("[a-z-]{2,12}\\.[a-zA-Z-]{2,12}");
    private static final String GITHUB_MEDIA_TYPE = "application/vnd.github+json";

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final GitHubNormalizer normalizer = new GitHubNormalizer();
    private final String baseUrl;
    private final String token;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final String defaultQuery;

    public GitHubConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.token = settings.token();
        this.pageSize = Math.min(settings.pageSizeOr(100), 100);
        // Authenticated search gets 30 requests/minute, anonymous 10 (GitHub's documented limits).
        this.requestsPerMinute = settings.requestsPerMinuteOr(settings.hasToken() ? 30 : 10);
        this.enabled = settings.enabledOr(true);
        this.defaultQuery = settings.defaultQueryOr("machine-learning");
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "GitHub",
                SourceClass.CODE_REPOSITORY,
                Set.of(SourceClass.CODE_REPOSITORY),
                requestsPerMinute,
                false,
                token != null && !token.isBlank(),
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор github выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && request.accepts(SourceClass.CODE_REPOSITORY);
    }

    @Override
    protected DocumentNormalizer<GitHubSearchResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<GitHubSearchResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        int page = pageOf(cursor);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("q", searchQuery(request))
                .queryParam("sort", "stars")
                .queryParam("order", "desc")
                .queryParam("per_page", pageSize)
                .queryParam("page", page)
                .build()
                .encode()
                .toUri();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ACCEPT, GITHUB_MEDIA_TYPE);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        RawHttpResponse response = http.get(SOURCE_ID, uri, headers, requestsPerMinute);
        GitHubSearchResponse body =
                JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), GitHubSearchResponse.class);

        List<GitHubSearchResponse.Raw> items = new ArrayList<>(body.items().size());
        for (GitHubSearchResponse.Repository repository : body.items()) {
            String externalId =
                    repository.fullName() == null || repository.fullName().isBlank()
                            ? String.valueOf(repository.id())
                            : repository.fullName();
            items.add(new GitHubSearchResponse.Raw(repository, SOURCE_ID, externalId, response.provenance()));
        }
        boolean last = items.isEmpty()
                || items.size() < pageSize
                || page * pageSize
                        >= Math.min(MAX_RESULTS, body.totalCount() == null ? MAX_RESULTS : body.totalCount());
        return new SourcePage<>(items, Cursor.ofValue(Integer.toString(page + 1)), last);
    }

    /** GitHub search qualifiers: free text plus a creation-date window. */
    private String searchQuery(CollectionRequest request) {
        // GitHub соединяет слова запроса через И, поэтому цели перечисляются через ИЛИ: иначе
        // репозиторий обязан упоминать разом все коды направления, а таких не бывает.
        String terms = request.isWildcard()
                ? defaultQuery
                : String.join(
                        " OR ",
                        selectTerms(request.upstreamTerms()).stream()
                                .map(term -> "\"" + term + "\"")
                                .toList());
        return "%s created:%s..%s".formatted(terms, request.windowFrom(), request.windowTo());
    }

    /**
     * Выбирает не больше {@link #MAX_TERMS} целей направления — и именно те, которые GitHub может
     * найти.
     *
     * <p>Причина измерена живым запросом: направление «искусственный интеллект» даёт двенадцать
     * целей, запрос из них содержит одиннадцать операторов ИЛИ, а поиск GitHub отвергает запрос
     * более чем с пятью — {@code 422}. Источник числился включённым и не приносил ни одного
     * репозитория, а в отчёте значился недоступным: оговорка «корпус собран не полностью»
     * возникала там, где источник был жив.
     *
     * <p>Порядок отбора содержательный, а не алфавитный. Первыми идут словосочетания: имя из двух
     * слов задаёт предмет, одиночное слово — нет («ai» приносит всё, что угодно). Классификационные
     * коды предметных словарей ({@code cs.LG}, {@code quant-ph}) исключаются вовсе: это лексика
     * arXiv и патентных ведомств, в темах и описаниях репозиториев её не бывает, и место в бюджете
     * из пяти они занимали впустую.
     */
    static List<String> selectTerms(List<String> upstreamTerms) {
        List<String> phrases = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String term : upstreamTerms) {
            if (term == null || term.isBlank() || CLASSIFICATION_CODE.matcher(term).matches()) {
                continue;
            }
            (term.contains(" ") ? phrases : words).add(term);
        }
        List<String> selected = new ArrayList<>(phrases);
        selected.addAll(words);
        return selected.size() <= MAX_TERMS ? selected : selected.subList(0, MAX_TERMS);
    }

    private static int pageOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(cursor.value()), 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
