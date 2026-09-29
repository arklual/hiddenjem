package dev.horizon.ingestion.connector.lens.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ поиска патентов Lens ({@code POST /patent/search}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LensPatentResponse(Integer total, Integer results, List<Patent> data) {

    public LensPatentResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Patent(
            @JsonProperty("lens_id") String lensId,
            String jurisdiction,
            @JsonProperty("doc_number") String docNumber,
            String kind,
            @JsonProperty("date_published") String datePublished,
            @JsonProperty("publication_type") String publicationType,
            String lang,
            Biblio biblio,
            @JsonProperty("abstract") List<Text> abstractTexts,
            @JsonProperty("legal_status") LegalStatus legalStatus) {

        public Patent {
            abstractTexts = abstractTexts == null ? List.of() : List.copyOf(abstractTexts);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Biblio(
            @JsonProperty("invention_title") List<Text> inventionTitle,
            Parties parties,
            @JsonProperty("classifications_cpc") Classifications classificationsCpc) {

        public Biblio {
            inventionTitle = inventionTitle == null ? List.of() : List.copyOf(inventionTitle);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parties(List<Party> applicants, List<Party> inventors) {

        public Parties {
            applicants = applicants == null ? List.of() : List.copyOf(applicants);
            inventors = inventors == null ? List.of() : List.copyOf(inventors);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Party(String residence, @JsonProperty("extracted_name") Name extractedName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Name(String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Classifications(List<Classification> classifications) {

        public Classifications {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Classification(String symbol) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String text, String lang) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LegalStatus(Boolean granted) {}

    /** Один патентный документ вместе с происхождением ответа, в котором он пришёл. */
    public record Raw(Patent patent, String sourceId, String externalId, Provenance provenance) implements RawDocument {}
}
