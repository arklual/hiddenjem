package dev.horizon.ingestion.connector.ietf.model;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Ответы API IETF Datatracker ({@code /api/v1/…}, Tastypie): у всех одна обёртка — {@code meta}
 * со счётчиком и ссылкой на следующую страницу и {@code objects}.
 */
public final class IetfResponses {

    private IetfResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(Integer limit, Integer offset, String next, @JsonProperty("total_count") Integer totalCount) {}

    /** {@code /api/v1/doc/document/} — черновики. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftPage(Meta meta, List<Draft> objects) {

        public DraftPage {
            objects = objects == null ? List.of() : List.copyOf(objects);
        }
    }

    /**
     * Черновик. {@code time} — момент последнего изменения записи (новая ревизия, смена состояния,
     * истечение срока), а не дата появления черновика.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(
            String name,
            String title,
            @JsonProperty("abstract") String abstractText,
            String rev,
            String time,
            String group,
            Integer pages) {}

    /** {@code /api/v1/doc/newrevisiondocevent/} — события «вышла ревизия». */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevisionPage(Meta meta, List<Revision> objects) {

        public RevisionPage {
            objects = objects == null ? List.of() : List.copyOf(objects);
        }
    }

    /** {@code doc} — ссылка вида {@code /api/v1/doc/document/<name>/}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revision(String doc, String rev, String time) {}

    /** {@code /api/v1/doc/documentauthor/} — авторы с аффилиацией на момент черновика. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorPage(Meta meta, List<DocumentAuthor> objects) {

        public AuthorPage {
            objects = objects == null ? List.of() : List.copyOf(objects);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentAuthor(String document, String person, String affiliation, String country, Integer order) {}

    /** {@code /api/v1/person/person/} — имена людей. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonPage(Meta meta, List<Person> objects) {

        public PersonPage {
            objects = objects == null ? List.of() : List.copyOf(objects);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(Long id, String name, String ascii) {}

    /** Автор черновика, собранный из двух ответов: аффилиация — из документа, имя — из персоны. */
    public record DraftAuthor(String name, String affiliation, String country) {}

    /**
     * Черновик, дополненный тем, чего в его карточке нет.
     *
     * @param firstRevision дата ревизии {@code 00} под этим именем — когда черновик появился
     */
    public record Raw(
            Draft draft,
            LocalDate firstRevision,
            List<DraftAuthor> authors,
            String sourceId,
            String externalId,
            Provenance provenance)
            implements RawDocument {

        public Raw {
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }
}
