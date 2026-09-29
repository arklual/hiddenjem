package dev.horizon.ingestion.connector.europepmc;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.europepmc.model.EuropePmcResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для Europe PMC.
 *
 * <p><b>Организация — учреждение, а не строка аффилиации.</b> Europe PMC отдаёт аффилиацию
 * свободным текстом: «Department of Radiology, Stanford University, Stanford, CA, USA». Положить её
 * целиком значило бы сделать каждую кафедру отдельной организацией — и правило «две независимые
 * организации» пропускало бы тему, которую подтверждают две кафедры одного университета. Поэтому
 * из строки берётся сегмент, который называет учреждение (University, Institute, Hospital, Inc…);
 * не нашлось такого — организации нет, а не выдуманная.
 *
 * <p><b>Класс источника.</b> {@code source=PPR} — препринт, остальное — публикация.
 */
public class EuropePmcNormalizer implements DocumentNormalizer<EuropePmcResponse.Raw> {

    private static final Pattern COMPANY = Pattern.compile(
            "\\b(inc|ltd|llc|gmbh|corp|corporation|company|co\\.|plc|s\\.a\\.|ag|pharmaceuticals?|technologies)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIVERSITY = Pattern.compile(
            "\\b(university|universit[äéà]t?|universidad|université|college|school of medicine)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUTE = Pattern.compile(
            "\\b(institute|institut|centre|center|laborator(y|ies)|academy|hospital|clinic)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Трёхбуквенные коды языка Europe PMC → ISO 639-1 колонки {@code documents.language}. */
    private static final Map<String, String> LANGUAGES = Map.of(
            "eng", "en", "rus", "ru", "ger", "de", "fre", "fr", "spa", "es", "chi", "zh", "jpn", "ja");

    @Override
    public Document normalize(EuropePmcResponse.Raw raw) {
        EuropePmcResponse.Result result = raw.result();
        if (result.title() == null || result.title().isBlank()) {
            throw new IllegalArgumentException("Europe PMC record without a title: " + raw.externalId());
        }
        boolean preprint = "PPR".equalsIgnoreCase(result.source());
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(preprint ? SourceClass.PREPRINT : SourceClass.JOURNAL_ARTICLE)
                .title(stripMarkup(result.title()))
                .abstractText(result.abstractText() == null ? null : stripMarkup(result.abstractText()))
                .publishedOn(publishedOn(result))
                .url("https://europepmc.org/article/%s/%s".formatted(result.source(), result.id()))
                .metrics(DocumentMetrics.ofCitations(result.citedByCount()))
                .provenance(raw.provenance());
        if (result.doi() != null && !result.doi().isBlank()) {
            builder.doi(result.doi().trim());
        }
        String language = result.language() == null ? null : LANGUAGES.get(result.language().toLowerCase(Locale.ROOT));
        if (language != null) {
            builder.language(language);
        }
        if (result.journalInfo() != null && result.journalInfo().journal() != null
                && result.journalInfo().journal().title() != null) {
            var journal = result.journalInfo().journal();
            builder.venue(Venue.orNull(journal.title(), "JOURNAL", journal.issn()));
        } else if (preprint) {
            builder.venue(new Venue("Europe PMC preprints", "PREPRINT_SERVER", null));
        }
        if (result.authorList() != null) {
            for (EuropePmcResponse.PmcAuthor author : result.authorList().author()) {
                if (author.fullName() == null || author.fullName().isBlank()) {
                    continue;
                }
                String institution = institutionOf(author);
                builder.author(new Author(
                        author.fullName().trim(),
                        null,
                        institution,
                        institution == null ? null : typeOf(institution),
                        null));
            }
        }
        return builder.build();
    }

    /** Сегмент аффилиации, называющий учреждение; {@code null}, если такого нет. */
    static String institutionOf(EuropePmcResponse.PmcAuthor author) {
        var list = author.authorAffiliationDetailsList();
        if (list == null || list.authorAffiliation().isEmpty()) {
            return null;
        }
        String affiliation = list.authorAffiliation().get(0).affiliation();
        if (affiliation == null) {
            return null;
        }
        for (String segment : affiliation.split("[,;]")) {
            String candidate = segment.trim();
            if (candidate.length() < 4 || candidate.length() > 200) {
                continue;
            }
            if (UNIVERSITY.matcher(candidate).find()
                    || INSTITUTE.matcher(candidate).find()
                    || COMPANY.matcher(candidate).find()) {
                return candidate.replaceAll("\\.$", "");
            }
        }
        return null;
    }

    private static OrganizationType typeOf(String institution) {
        if (COMPANY.matcher(institution).find()) {
            return OrganizationType.COMPANY;
        }
        if (UNIVERSITY.matcher(institution).find()) {
            return OrganizationType.UNIVERSITY;
        }
        return OrganizationType.RESEARCH_INSTITUTE;
    }

    private static LocalDate publishedOn(EuropePmcResponse.Result result) {
        if (result.firstPublicationDate() != null && !result.firstPublicationDate().isBlank()) {
            try {
                return LocalDate.parse(result.firstPublicationDate().trim());
            } catch (DateTimeParseException ignored) {
                // Падаем на год ниже.
            }
        }
        if (result.pubYear() != null && result.pubYear().matches("\\d{4}")) {
            return LocalDate.of(Integer.parseInt(result.pubYear()), 1, 1);
        }
        throw new IllegalArgumentException("Europe PMC record without a date: " + result.id());
    }

    /** Europe PMC присылает заголовки и аннотации с разметкой ({@code <i>}, {@code <sup>}). */
    private static String stripMarkup(String text) {
        return text.replaceAll("<[^>]{1,40}>", "").replaceAll("\\s+", " ").trim();
    }
}
