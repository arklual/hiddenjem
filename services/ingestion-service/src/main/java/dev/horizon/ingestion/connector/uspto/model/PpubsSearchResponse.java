package dev.horizon.ingestion.connector.uspto.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Ответ {@code POST /api/searches/searchWithBeFamily} Patent Public Search.
 *
 * <p><b>Постраничность — по семействам, а не по документам.</b> {@code start} и {@code pageCount}
 * отсчитывают семейства патентов ({@code numberOfFamilies} — сколько их всего), а в {@code patents}
 * приходят все документы каждого семейства: заявка US-PGPUB и выданный по ней патент USPAT идут
 * соседними строками. Поэтому число строк страницы ({@code totalResults}) больше её размера, и
 * конец выдачи определяется по семействам.
 *
 * <p><b>Ошибка запроса приходит с кодом 200.</b> Синтаксическая ошибка BRS — {@code error} с
 * кодом и текстом ({@code 125 «Unmatched parentheses in the query»}) и {@code patents: null}.
 * Законная пустая выдача — {@code error: null} и {@code patents: []}. Поле {@code patents}
 * оставлено как есть, без подмены {@code null} пустым списком: иначе ответ не по контракту было бы
 * не отличить от честного нуля.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PpubsSearchResponse(
        Integer numFound, Integer totalResults, Integer numberOfFamilies, Error error, List<Patent> patents) {

    /** Ошибка разбора запроса: код и текст, как их показывает веб-приложение. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(Integer errorCode, String errorMessage) {}

    /**
     * Одна строка выдачи. Аннотации и полного списка изобретателей здесь нет: они только в карточке
     * документа ({@code /api/patents/highlight/…}), а это запрос на каждый документ — см.
     * {@code UsptoConnector}. Заголовок приходит с разметкой подсветки совпадений
     * ({@code <span class="highlight18">}), её снимает нормализатор.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Patent(
            String guid,
            String publicationReferenceDocumentNumber,
            String documentId,
            String type,
            List<String> kindCode,
            String datePublished,
            String inventionTitle,
            List<String> assigneeName,
            List<String> applicantName,
            String inventorsShort,
            List<String> applicationFilingDate,
            List<String> relatedApplFilingDate,
            String applicationNumber,
            Long familyIdentifierCur,
            String cpcInventiveFlattened,
            String cpcAdditionalFlattened,
            List<String> governmentInterest,
            String languageIndicator) {

        public Patent {
            kindCode = kindCode == null ? List.of() : List.copyOf(kindCode.stream().filter(v -> v != null).toList());
            assigneeName = nonNull(assigneeName);
            applicantName = nonNull(applicantName);
            applicationFilingDate = nonNull(applicationFilingDate);
            relatedApplFilingDate = nonNull(relatedApplFilingDate);
            governmentInterest = nonNull(governmentInterest);
        }

        private static List<String> nonNull(List<String> values) {
            return values == null
                    ? List.of()
                    : values.stream().filter(v -> v != null && !v.isBlank()).toList();
        }
    }

    /**
     * Документ с тем, по какой формулировке он найден, и с происхождением ответа.
     *
     * @param familyMembers сколько документов семейства пришло в выдаче (заявка и патент — два)
     */
    public record Raw(
            Patent patent, String phrase, int familyMembers, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
