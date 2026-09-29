package dev.horizon.ingestion.connector.lens;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.horizon.ingestion.connector.lens.model.LensPatentResponse;
import dev.horizon.ingestion.connector.uspto.Assignees;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для патентов Lens.
 *
 * <ul>
 *   <li><b>Название и реферат — английские, если есть.</b> Европейская заявка приходит с названием
 *       на трёх языках; извлечение тем работает по английскому.
 *   <li><b>Участники — как у USPTO.</b> Первый изобретатель с первым заявителем, остальные заявители
 *       — отдельно: совместная заявка двух компаний — сигнал обеих.
 *   <li><b>Номер — ведомство, номер и вид:</b> {@code EP2471949A1}.
 * </ul>
 */
public class LensPatentNormalizer implements DocumentNormalizer<LensPatentResponse.Raw> {

    private static final int MAX_TOPICS = 8;

    @Override
    public Document normalize(LensPatentResponse.Raw raw) {
        LensPatentResponse.Patent patent = raw.patent();
        LensPatentResponse.Text title = english(
                patent.biblio() == null ? List.of() : patent.biblio().inventionTitle());
        if (title == null) {
            throw new IllegalArgumentException("Lens patent without a title: " + patent.lensId());
        }
        LocalDate published = date(patent.datePublished());
        if (published == null) {
            throw new IllegalArgumentException("Lens patent without a publication date: " + patent.lensId());
        }
        String number = number(patent);
        // Вид документа, а не судьба семейства: у заявки A1 правовой статус бывает «выдан» — выдан
        // патент B1 той же семьи, а сам документ остаётся заявкой.
        boolean granted = "GRANTED_PATENT".equalsIgnoreCase(patent.publicationType());
        Map<String, OrganizationType> organizations = organizations(patent);
        LensPatentResponse.Text abstractText = english(patent.abstractTexts());

        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.PATENT)
                .title(title.text().trim())
                .abstractText(abstractText == null
                        ? stub(patent, number, published, granted, organizations)
                        : abstractText.text().trim())
                .publishedOn(published)
                .url(LensScholarlyNormalizer.RECORD_URL + patent.lensId())
                .venue(new Venue(
                        (patent.jurisdiction() == null ? "Lens" : patent.jurisdiction())
                                + (granted ? ", патент" : ", заявка")
                                + (patent.kind() == null ? "" : " (" + patent.kind() + ")"),
                        "PATENT_OFFICE",
                        null))
                .metrics(DocumentMetrics.EMPTY)
                .provenance(raw.provenance());
        if (title.lang() != null && !title.lang().isBlank()) {
            builder.language(title.lang().trim().toLowerCase(Locale.ROOT));
        }
        if (number != null) {
            builder.patentNumber(number);
        }

        List<Map.Entry<String, OrganizationType>> orgs = new ArrayList<>(organizations.entrySet());
        String inventor = firstInventor(patent);
        if (inventor != null) {
            var first = orgs.isEmpty() ? null : orgs.get(0);
            builder.author(new Author(
                    inventor, null, first == null ? null : first.getKey(), first == null ? null : first.getValue(), null));
        }
        for (int i = inventor == null ? 0 : 1; i < orgs.size(); i++) {
            builder.author(new Author(orgs.get(i).getKey(), null, orgs.get(i).getKey(), orgs.get(i).getValue(), null));
        }
        if (patent.biblio() != null && patent.biblio().classificationsCpc() != null) {
            patent.biblio().classificationsCpc().classifications().stream()
                    .map(LensPatentResponse.Classification::symbol)
                    .filter(symbol -> symbol != null && !symbol.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(MAX_TOPICS)
                    .forEach(symbol -> builder.topic(DocumentTopic.of(symbol)));
        }
        return builder.build();
    }

    /** «EP» + «2471949» + «A1». */
    static String number(LensPatentResponse.Patent patent) {
        if (patent.docNumber() == null || patent.docNumber().isBlank()) {
            return null;
        }
        return (patent.jurisdiction() == null ? "" : patent.jurisdiction().trim())
                + patent.docNumber().trim()
                + (patent.kind() == null ? "" : patent.kind().trim());
    }

    /** Заявители-организации без повторов регистра; физические лица отброшены. */
    static Map<String, OrganizationType> organizations(LensPatentResponse.Patent patent) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (patent.biblio() != null && patent.biblio().parties() != null) {
            for (LensPatentResponse.Party party : patent.biblio().parties().applicants()) {
                String name = party.extractedName() == null ? null : text(party.extractedName().value());
                if (name == null || Assignees.isIndividual(name)) {
                    continue;
                }
                byKey.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        Map<String, OrganizationType> organizations = new LinkedHashMap<>();
        byKey.values().forEach(name -> organizations.put(name, Assignees.typeOf(name)));
        return organizations;
    }

    private static String firstInventor(LensPatentResponse.Patent patent) {
        if (patent.biblio() == null || patent.biblio().parties() == null) {
            return null;
        }
        return patent.biblio().parties().inventors().stream()
                .map(party -> party.extractedName() == null ? null : text(party.extractedName().value()))
                .filter(name -> name != null)
                .map(LensPatentNormalizer::unshout)
                .findFirst()
                .orElse(null);
    }

    /** «OCHOA JORGE» → «Ochoa Jorge»: Lens пишет изобретателей заглавными. */
    static String unshout(String name) {
        if (!name.equals(name.toUpperCase(Locale.ROOT))) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length());
        boolean start = true;
        for (char c : name.toCharArray()) {
            out.append(start ? c : Character.toLowerCase(c));
            start = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }

    private static LensPatentResponse.Text english(List<LensPatentResponse.Text> texts) {
        LensPatentResponse.Text any = null;
        for (LensPatentResponse.Text text : texts) {
            if (text == null || text(text.text()) == null) {
                continue;
            }
            if ("en".equalsIgnoreCase(text.lang())) {
                return text;
            }
            if (any == null) {
                any = text;
            }
        }
        return any;
    }

    private static String stub(
            LensPatentResponse.Patent patent,
            String number,
            LocalDate published,
            boolean granted,
            Map<String, OrganizationType> organizations) {
        StringBuilder text = new StringBuilder()
                .append(granted ? "Patent " : "Patent application ")
                .append(number == null ? patent.lensId() : number)
                .append(", published ")
                .append(published)
                .append('.');
        if (!organizations.isEmpty()) {
            text.append(" Applicants: ").append(String.join(", ", organizations.keySet())).append('.');
        }
        return text.toString();
    }

    private static LocalDate date(String value) {
        String date = text(value);
        if (date == null || date.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(date.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
