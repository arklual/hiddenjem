package dev.horizon.ingestion.connector.ietf;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.ietf.model.IetfResponses;
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
 * IETF Datatracker — черновики интернет-стандартов (Internet-Drafts).
 *
 * <p><b>Зачем.</b> Новый протокол или криптографическая схема появляется в черновике IETF за годы до
 * RFC и массовых внедрений: постквантовый TLS, гибридные ключи, форматы для новых сетей. Авторы —
 * инженеры производителей, и аффилиации сразу показывают, какие компании вкладываются в технологию.
 * Это ранний технический сигнал того же рода, что стандарт, — отсюда класс {@link SourceClass#STANDARD}.
 *
 * <p><b>Как спрашивается.</b> Поиска по тексту у API нет, есть фильтры Tastypie: {@code
 * title__icontains} и {@code abstract__icontains}. Условия ИЛИ между полями нет, поэтому каждая
 * формулировка спрашивается дважды — по названию и по аннотации; во втором проходе черновики, у
 * которых фраза есть и в названии, пропускаются: их уже вернул первый. Курсор — «формулировка:поле:сдвиг».
 * Нижняя граница окна уходит в {@code time__gte}: черновик, начатый в окне, изменялся не раньше
 * своего начала, так что предварительный отбор ничего нужного не теряет. Верхней границы по
 * {@code time} нет — черновик, начатый в окне и обновлённый после него, остаётся сигналом окна.
 *
 * <p><b>Три дополнительных запроса на страницу, а не на черновик.</b> Дата первой ревизии, авторы и
 * их имена лежат в других ресурсах; все три спрашиваются пакетно ({@code __in} по именам страницы),
 * так что пятьдесят черновиков стоят четырёх запросов, а не двухсот.
 *
 * <p><b>Ноль и отказ различаются.</b> Пустой список — законный ноль. Ошибка любого из запросов
 * страницы роняет прогон источника: черновики без дат и авторов выдавать за полные нельзя.
 */
public class IetfConnector extends AbstractSourceConnector<IetfResponses.Raw> {

    public static final String SOURCE_ID = "ietf";
    private static final String DEFAULT_BASE_URL = "https://datatracker.ietf.org/api/v1/";
    private static final int MIN_PHRASE_LENGTH = 3;
    private static final List<String> FIELDS = List.of("title", "abstract");
    /** Больше четырёх страниц по полю не нужно: фраза в названии или аннотации редко даёт больше двухсот черновиков. */
    private static final int MAX_PAGES_PER_FIELD = 4;

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final IetfNormalizer normalizer = new IetfNormalizer();
    private final String baseUrl;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public IetfConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        String base = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.baseUrl = base.endsWith("/") ? base : base + "/";
        this.pageSize = Math.min(settings.pageSizeOr(50), 100);
        this.requestsPerMinute = settings.requestsPerMinuteOr(20);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "IETF Datatracker (черновики стандартов)",
                SourceClass.STANDARD,
                Set.of(SourceClass.STANDARD),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор ietf выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.STANDARD);
    }

    @Override
    protected DocumentNormalizer<IetfResponses.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<IetfResponses.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
        if (phrases.isEmpty()) {
            return new SourcePage<>(List.of(), Cursor.ofValue("0:0:0"), true);
        }
        int[] position = positionOf(cursor);
        int term = Math.min(position[0], phrases.size() - 1);
        int field = Math.min(position[1], FIELDS.size() - 1);
        int offset = position[2];
        String phrase = phrases.get(term);

        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "doc/document/")
                .queryParam("format", "json")
                .queryParam("type", "draft")
                .queryParam(FIELDS.get(field) + "__icontains", phrase)
                .queryParam("time__gte", request.windowFrom().toString())
                .queryParam("order_by", "id")
                .queryParam("limit", pageSize)
                .queryParam("offset", offset)
                .build()
                .encode()
                .toUri();
        RawHttpResponse response = get(uri);
        IetfResponses.DraftPage page =
                JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), IetfResponses.DraftPage.class);

        String needle = phrase.toLowerCase(Locale.ROOT);
        List<IetfResponses.Draft> drafts = new ArrayList<>();
        for (IetfResponses.Draft draft : page.objects()) {
            if (draft.name() == null || draft.name().isBlank()) {
                continue;
            }
            boolean inTitle = draft.title() != null
                    && draft.title().toLowerCase(Locale.ROOT).contains(needle);
            if (FIELDS.get(field).equals("abstract") && inTitle) {
                continue;
            }
            drafts.add(draft);
        }
        List<IetfResponses.Raw> items = enrich(request, drafts, response);

        int returned = page.objects().size();
        int total = page.meta() == null || page.meta().totalCount() == null
                ? 0
                : page.meta().totalCount();
        boolean fieldDone = returned == 0 || offset + returned >= total || offset / pageSize + 1 >= MAX_PAGES_PER_FIELD;
        String next;
        boolean last = false;
        if (!fieldDone) {
            next = term + ":" + field + ":" + (offset + returned);
        } else if (field + 1 < FIELDS.size()) {
            next = term + ":" + (field + 1) + ":0";
        } else {
            next = (term + 1) + ":0:0";
            last = term + 1 >= phrases.size();
        }
        return new SourcePage<>(items, Cursor.ofValue(next), last);
    }

    /** Первая ревизия, отбор по окну, затем авторы — только для тех, кто в окно попал. */
    private List<IetfResponses.Raw> enrich(
            CollectionRequest request, List<IetfResponses.Draft> drafts, RawHttpResponse response) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        Map<String, LocalDate> firstRevisions = firstRevisions(names(drafts));
        List<IetfResponses.Draft> kept = new ArrayList<>();
        for (IetfResponses.Draft draft : drafts) {
            LocalDate first = firstRevisions.get(draft.name());
            // Без известной первой ревизии черновик идёт дальше: нормализатор его отвергнет, и
            // потеря будет видна в счётчике, а не исчезнет молча.
            if (first == null || request.withinWindow(first)) {
                kept.add(draft);
            }
        }
        if (kept.isEmpty()) {
            return List.of();
        }
        Map<String, List<IetfResponses.DraftAuthor>> authors = authors(names(kept));
        List<IetfResponses.Raw> items = new ArrayList<>(kept.size());
        for (IetfResponses.Draft draft : kept) {
            items.add(new IetfResponses.Raw(
                    draft,
                    firstRevisions.get(draft.name()),
                    authors.getOrDefault(draft.name(), List.of()),
                    SOURCE_ID,
                    draft.name(),
                    response.provenance()));
        }
        return items;
    }

    private Map<String, LocalDate> firstRevisions(List<String> names) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "doc/newrevisiondocevent/")
                .queryParam("format", "json")
                .queryParam("doc__name__in", String.join(",", names))
                .queryParam("rev", "00")
                .queryParam("limit", Math.min(names.size() * 2 + 10, 1000))
                .build()
                .encode()
                .toUri();
        IetfResponses.RevisionPage page =
                JsonBodies.parse(objectMapper, SOURCE_ID, get(uri).body(), IetfResponses.RevisionPage.class);
        Map<String, LocalDate> dates = new HashMap<>();
        for (IetfResponses.Revision revision : page.objects()) {
            String name = lastSegment(revision.doc());
            LocalDate date = dateOf(revision.time());
            if (name != null && date != null) {
                dates.merge(name, date, (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return dates;
    }

    private Map<String, List<IetfResponses.DraftAuthor>> authors(List<String> names) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "doc/documentauthor/")
                .queryParam("format", "json")
                .queryParam("document__name__in", String.join(",", names))
                .queryParam("limit", 1000)
                .build()
                .encode()
                .toUri();
        IetfResponses.AuthorPage page =
                JsonBodies.parse(objectMapper, SOURCE_ID, get(uri).body(), IetfResponses.AuthorPage.class);
        if (page.objects().isEmpty()) {
            return Map.of();
        }
        Set<String> personIds = new LinkedHashSet<>();
        for (IetfResponses.DocumentAuthor author : page.objects()) {
            String id = lastSegment(author.person());
            if (id != null) {
                personIds.add(id);
            }
        }
        Map<String, String> personNames = personNames(List.copyOf(personIds));
        Map<String, List<IetfResponses.DraftAuthor>> byDraft = new HashMap<>();
        page.objects().stream()
                .sorted(Comparator.comparing(a -> a.order() == null ? Integer.MAX_VALUE : a.order()))
                .forEach(author -> {
                    String draft = lastSegment(author.document());
                    String name = personNames.get(lastSegment(author.person()));
                    if (draft != null && name != null) {
                        byDraft.computeIfAbsent(draft, key -> new ArrayList<>())
                                .add(new IetfResponses.DraftAuthor(name, author.affiliation(), author.country()));
                    }
                });
        return byDraft;
    }

    private Map<String, String> personNames(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "person/person/")
                .queryParam("format", "json")
                .queryParam("id__in", String.join(",", ids))
                .queryParam("limit", 1000)
                .build()
                .encode()
                .toUri();
        IetfResponses.PersonPage page =
                JsonBodies.parse(objectMapper, SOURCE_ID, get(uri).body(), IetfResponses.PersonPage.class);
        Map<String, String> names = new HashMap<>();
        for (IetfResponses.Person person : page.objects()) {
            String name = person.name() != null && !person.name().isBlank() ? person.name() : person.ascii();
            if (person.id() != null && name != null && !name.isBlank()) {
                names.put(person.id().toString(), name.trim());
            }
        }
        return names;
    }

    private RawHttpResponse get(URI uri) {
        return http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE), requestsPerMinute);
    }

    private static List<String> names(List<IetfResponses.Draft> drafts) {
        return drafts.stream().map(IetfResponses.Draft::name).distinct().toList();
    }

    /** {@code /api/v1/doc/document/draft-x/} → {@code draft-x}. */
    static String lastSegment(String resourceUri) {
        if (resourceUri == null) {
            return null;
        }
        String trimmed = resourceUri.endsWith("/") ? resourceUri.substring(0, resourceUri.length() - 1) : resourceUri;
        int slash = trimmed.lastIndexOf('/');
        String segment = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return segment.isBlank() ? null : segment;
    }

    private static LocalDate dateOf(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalDate.ofInstant(Instant.parse(time.trim()), ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] positionOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return new int[] {0, 0, 0};
        }
        String[] parts = cursor.value().split(":");
        if (parts.length != 3) {
            return new int[] {0, 0, 0};
        }
        try {
            return new int[] {
                Math.max(0, Integer.parseInt(parts[0])),
                Math.max(0, Integer.parseInt(parts[1])),
                Math.max(0, Integer.parseInt(parts[2]))
            };
        } catch (NumberFormatException e) {
            return new int[] {0, 0, 0};
        }
    }
}
