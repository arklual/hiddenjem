package dev.horizon.ingestion.connector.deepresearch.model;

import java.time.LocalDate;
import java.util.List;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Страница, прочитанная агентом глубокого исследования и подтвердившая хотя бы одно имя.
 *
 * <p>Всё здесь — то, что сервис моделей получил из сети в этом прогоне: адрес, заголовок, дата,
 * дословные отрывки текста, отпечаток полученных байтов. Слова модели сюда попадают только в двух
 * полях, и оба — не свидетельство: {@code readReason} (почему агент решил читать) и
 * {@code technologies} (какие имена страница подтвердила — проверено по тексту сервисом).
 */
public record ResearchPage(
        String sourceId,
        String externalId,
        Provenance provenance,
        String url,
        String title,
        LocalDate publishedOn,
        String sourceClass,
        String origin,
        String host,
        String language,
        String excerpt,
        List<String> authors,
        String organization,
        boolean organizationIsCompany,
        String doi,
        String arxivId,
        String venue,
        String readReason,
        List<String> technologies)
        implements RawDocument {

    public ResearchPage {
        authors = authors == null ? List.of() : List.copyOf(authors);
        technologies = technologies == null ? List.of() : List.copyOf(technologies);
    }
}
