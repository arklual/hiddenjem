package dev.horizon.ingestion.connector.lens;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import dev.horizon.ingestion.connector.lens.model.LensScholarlyResponse;
import dev.horizon.ingestion.connector.uspto.Assignees;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для научных работ Lens.
 *
 * <ul>
 *   <li><b>Класс — по типу публикации.</b> {@code preprint} — препринт, всё остальное (статья,
 *       доклад, глава) — публикация.
 *   <li><b>Ссылка — DOI, если он есть.</b> Иначе постоянный адрес записи Lens по её идентификатору.
 *   <li><b>Тип организации — только явный.</b> Lens не размечает тип аффилиации; университет,
 *       государственное ведомство или исследовательский институт узнаются по названию, прочее
 *       остаётся без типа: больница, записанная компанией, исказила бы показатель распространения по
 *       типам организаций.
 *   <li><b>Дата — {@code date_published}, иначе 1 января года.</b> Работа без даты отвергается.
 * </ul>
 */
public class LensScholarlyNormalizer implements DocumentNormalizer<LensScholarlyResponse.Raw> {

    static final String RECORD_URL = "https://lens.org/";

    @Override
    public Document normalize(LensScholarlyResponse.Raw raw) {
        LensScholarlyResponse.Work work = raw.work();
        if (work.title() == null || work.title().isBlank()) {
            throw new IllegalArgumentException("Lens work without a title: " + work.lensId());
        }
        String doi = id(work, "doi");
        boolean preprint = work.publicationType() != null
                && work.publicationType().toLowerCase(Locale.ROOT).contains("preprint");

        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(preprint ? SourceClass.PREPRINT : SourceClass.JOURNAL_ARTICLE)
                .title(work.title().trim())
                .abstractText(work.abstractText())
                .publishedOn(publishedOn(work))
                .url(doi != null ? "https://doi.org/" + doi : RECORD_URL + work.lensId())
                .metrics(DocumentMetrics.ofCitations(work.scholarlyCitationsCount()))
                .provenance(raw.provenance());
        if (doi != null) {
            builder.doi(doi);
        }
        String arxiv = id(work, "arxiv");
        if (arxiv != null) {
            builder.arxivId(arxiv);
        }
        if (!work.languages().isEmpty() && work.languages().get(0) != null) {
            builder.language(work.languages().get(0).trim().toLowerCase(Locale.ROOT));
        }
        if (work.source() != null && work.source().title() != null && !work.source().title().isBlank()) {
            builder.venue(new Venue(work.source().title(), preprint ? "PREPRINT_SERVER" : "JOURNAL", null));
        }
        for (LensScholarlyResponse.Author author : work.authors()) {
            String name = name(author);
            if (name == null) {
                continue;
            }
            var affiliation = author.affiliations().isEmpty() ? null : author.affiliations().get(0);
            String organization = affiliation == null ? null : text(affiliation.name());
            builder.author(new Author(
                    name,
                    orcid(author),
                    organization,
                    organization == null ? null : explicitType(organization),
                    affiliation == null ? null : text(affiliation.countryCode())));
        }
        return builder.build();
    }

    private static OrganizationType explicitType(String organization) {
        OrganizationType type = Assignees.typeOf(organization);
        return type == OrganizationType.COMPANY ? null : type;
    }

    private static String name(LensScholarlyResponse.Author author) {
        String first = text(author.firstName());
        String last = text(author.lastName());
        if (first == null && last == null) {
            return null;
        }
        return first == null ? last : last == null ? first : first + " " + last;
    }

    private static String orcid(LensScholarlyResponse.Author author) {
        return author.ids().stream()
                .filter(id -> id != null && "orcid".equalsIgnoreCase(id.type()))
                .map(id -> text(id.value()))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private static String id(LensScholarlyResponse.Work work, String type) {
        return work.externalIds().stream()
                .filter(id -> id != null && type.equalsIgnoreCase(id.type()))
                .map(id -> text(id.value()))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    /** «2015-06-13T00:00:00.000000+00:00» → 2015-06-13; без даты — 1 января года. */
    static LocalDate publishedOn(LensScholarlyResponse.Work work) {
        String date = text(work.datePublished());
        if (date != null && date.length() >= 10) {
            try {
                return LocalDate.parse(date.substring(0, 10));
            } catch (DateTimeParseException ignored) {
                // Падаем на год ниже.
            }
        }
        if (work.yearPublished() != null && work.yearPublished() > 1900) {
            return LocalDate.of(work.yearPublished(), 1, 1);
        }
        throw new IllegalArgumentException("Lens work without a date: " + work.lensId());
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
