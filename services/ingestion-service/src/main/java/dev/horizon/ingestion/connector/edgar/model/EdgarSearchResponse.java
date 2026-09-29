package dev.horizon.ingestion.connector.edgar.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Ответ полнотекстового поиска SEC EDGAR ({@code efts.sec.gov/LATEST/search-index}).
 *
 * <p>Это ответ Elasticsearch почти без обёртки: {@code hits.total.value} — сколько файлов нашлось,
 * {@code hits.hits[]._source} — карточка файла. Файл, а не подача: одна подача (номер
 * {@code adsh}) состоит из основного документа и приложений, и поиск возвращает их по отдельности.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdgarSearchResponse(Hits hits) {

    public EdgarSearchResponse {
        hits = hits == null ? new Hits(null, List.of()) : hits;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hits(Total total, List<Hit> hits) {

        public Hits {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        public long totalValue() {
            return total == null || total.value() == null ? 0 : total.value();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Total(Long value, String relation) {}

    /** {@code _id} — «номер подачи:имя файла», из него собирается адрес документа в архиве. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(@JsonProperty("_id") String id, @JsonProperty("_source") Filing source) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filing(
            List<String> ciks,
            @JsonProperty("display_names") List<String> displayNames,
            @JsonProperty("root_forms") List<String> rootForms,
            @JsonProperty("file_date") String fileDate,
            @JsonProperty("biz_states") List<String> bizStates,
            @JsonProperty("biz_locations") List<String> bizLocations,
            List<String> sics,
            String form,
            String adsh,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("file_description") String fileDescription,
            String xsl,
            List<String> items) {

        public Filing {
            ciks = ciks == null ? List.of() : List.copyOf(ciks);
            displayNames = displayNames == null ? List.of() : List.copyOf(displayNames);
            rootForms = rootForms == null ? List.of() : List.copyOf(rootForms);
            bizStates = bizStates == null
                    ? List.of()
                    : bizStates.stream().map(s -> s == null ? "" : s).toList();
            bizLocations = bizLocations == null
                    ? List.of()
                    : bizLocations.stream().map(s -> s == null ? "" : s).toList();
            sics = sics == null
                    ? List.of()
                    : sics.stream().map(s -> s == null ? "" : s).toList();
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Одна подача вместе с формулировкой, по которой её нашли, и происхождением ответа.
     *
     * @param phrase формулировка запроса — поиск совпал с текстом подачи именно по ней
     */
    public record Raw(Hit hit, String phrase, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
