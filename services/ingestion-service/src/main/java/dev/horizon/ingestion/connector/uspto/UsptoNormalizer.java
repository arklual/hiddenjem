package dev.horizon.ingestion.connector.uspto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

import dev.horizon.ingestion.connector.uspto.model.PpubsSearchResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для Patent Public Search: строка выдачи → документ класса «патент».
 *
 * <p><b>Дата — публикации, а не подачи.</b> {@code publishedOn} — день, когда документ увидел мир:
 * публикация заявки (US-PGPUB) или выдача патента (USPAT). Дата подачи известна только заявителю и
 * ведомству, и сдвинуть по ней сигнал в прошлое значило бы подсмотреть будущее. Она сохраняется в
 * тексте документа, вместе с датой самой ранней связанной заявки.
 *
 * <p><b>Текст — только то, что известно наверняка.</b> В выдаче нет реферата: он есть лишь в
 * карточке документа, по запросу на каждый патент (см. {@link UsptoConnector}). Поэтому текст
 * собирается из полей выдачи — вид документа, номер, даты, заявитель, первый изобретатель, классы
 * CPC, заявление о государственном финансировании — и из факта совпадения: поиск нашёл
 * формулировку в названии или реферате. Пересказа изобретения нет: его в ответе нет, и выдумывать
 * его нельзя. Разметка подсветки совпадений, которую площадка вставляет в название, снимается.
 *
 * <p><b>Организации — правообладатели.</b> Кому принадлежит патент — сигнал коммерциализации,
 * поэтому правообладатель ({@code assigneeName}, а у свежей заявки, где его ещё нет, —
 * заявитель {@code applicantName}) становится организацией. Тип определяется по названию
 * ({@link Assignees}); физические лица организацией не считаются. Изобретатель в выдаче один —
 * первый ({@code inventorsShort} «JENSEN; Christopher et al.»); остальные только в карточке.
 *
 * <p><b>Адрес — постоянная ссылка самого USPTO</b>
 * {@code ppubs.uspto.gov/pubwebapp/external.html?q=<номер>.pn.}: её формат описан в самой странице
 * площадки, и она открывает документ в день публикации. Google Patents выкладывает новые
 * публикации с задержкой: на 2026-09-28 заявка US 2026/0282756 от 17 сентября там ещё 404, а
 * самые свежие патенты для ранней стадии и есть самые ценные.
 */
public class UsptoNormalizer implements DocumentNormalizer<PpubsSearchResponse.Raw> {

    static final String PUBLIC_URL = "https://ppubs.uspto.gov/pubwebapp/external.html?q=";
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** «[0001]» — номер абзаца описания, в тексте документа он лишний. */
    private static final Pattern PARAGRAPH_NUMBER = Pattern.compile("\\[\\d{4}\\]\\s*");

    private static final Pattern ET_AL = Pattern.compile("\\s+et al\\.?$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TOPICS = 10;

    @Override
    public Document normalize(PpubsSearchResponse.Raw raw) {
        PpubsSearchResponse.Patent patent = raw.patent();
        String title = text(patent.inventionTitle());
        if (title == null) {
            throw new IllegalArgumentException("USPTO record without a title: " + patent.guid());
        }
        LocalDate published = date(patent.datePublished());
        if (published == null) {
            throw new IllegalArgumentException("USPTO record without a publication date: " + patent.guid());
        }
        String number = number(patent);
        if (number == null) {
            throw new IllegalArgumentException("USPTO record without a document number: " + patent.guid());
        }
        boolean granted = "USPAT".equalsIgnoreCase(patent.type());
        Map<String, OrganizationType> organizations = organizations(patent);
        String inventor = firstInventor(patent.inventorsShort());

        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.PATENT)
                .title(title)
                .abstractText(abstractOf(raw, granted, organizations))
                .language("en")
                .publishedOn(published)
                .patentNumber(patent.guid() == null ? null : patent.guid().replace("-", ""))
                .url(PUBLIC_URL + number + ".pn.")
                .venue(new Venue(
                        granted ? "USPTO, патент (USPAT)" : "USPTO, заявка (US-PGPUB)", "PATENT_OFFICE", null))
                .metrics(DocumentMetrics.EMPTY)
                .provenance(raw.provenance());

        List<Map.Entry<String, OrganizationType>> orgs = new ArrayList<>(organizations.entrySet());
        if (inventor != null) {
            var first = orgs.isEmpty() ? null : orgs.get(0);
            builder.author(new Author(
                    inventor, null, first == null ? null : first.getKey(), first == null ? null : first.getValue(), null));
        }
        // Остальные правообладатели — отдельными участниками: совместный патент IBM и Samsung —
        // сигнал обеих компаний, а не одной.
        for (int i = inventor == null ? 0 : 1; i < orgs.size(); i++) {
            builder.author(new Author(orgs.get(i).getKey(), null, orgs.get(i).getKey(), orgs.get(i).getValue(), null));
        }
        for (String code : cpc(patent)) {
            builder.topic(DocumentTopic.of(code));
        }
        return builder.build();
    }

    /** Номер публикации без вида: «20260282756» у заявки, «12737605» у патента. */
    static String number(PpubsSearchResponse.Patent patent) {
        String number = patent.publicationReferenceDocumentNumber();
        if (number == null || number.isBlank()) {
            return null;
        }
        return number.trim();
    }

    /**
     * Правообладатели без повторов (USPTO пишет одно имя то заглавными, то нет), физические лица
     * отброшены. У свежей заявки правообладателя ещё нет — тогда заявители.
     */
    static Map<String, OrganizationType> organizations(PpubsSearchResponse.Patent patent) {
        List<String> names = patent.assigneeName().isEmpty() ? patent.applicantName() : patent.assigneeName();
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String raw : names) {
            String name = text(raw);
            if (name == null || Assignees.isIndividual(name)) {
                continue;
            }
            byKey.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        Map<String, OrganizationType> organizations = new LinkedHashMap<>();
        byKey.values().forEach(name -> organizations.put(name, Assignees.typeOf(name)));
        return organizations;
    }

    /** «JENSEN; Christopher et al.» → «Christopher Jensen». */
    static String firstInventor(String inventorsShort) {
        String value = text(inventorsShort);
        if (value == null) {
            return null;
        }
        value = ET_AL.matcher(value).replaceAll("").trim();
        int semicolon = value.indexOf(';');
        String name = semicolon < 0
                ? value
                : (value.substring(semicolon + 1).trim() + " " + value.substring(0, semicolon).trim()).trim();
        StringBuilder out = new StringBuilder();
        for (String token : name.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(capitalizeIfShouting(token));
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String capitalizeIfShouting(String token) {
        if (token.length() < 2 || !token.equals(token.toUpperCase(Locale.ROOT))) {
            return token;
        }
        StringBuilder out = new StringBuilder(token.length());
        boolean start = true;
        for (char c : token.toCharArray()) {
            out.append(start ? c : Character.toLowerCase(c));
            start = c == '-' || c == '\'';
        }
        return out.toString();
    }

    private static List<String> cpc(PpubsSearchResponse.Patent patent) {
        if (patent.cpcInventiveFlattened() == null || patent.cpcInventiveFlattened().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(patent.cpcInventiveFlattened().split(";"))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .limit(MAX_TOPICS)
                .toList();
    }

    private static String abstractOf(
            PpubsSearchResponse.Raw raw, boolean granted, Map<String, OrganizationType> organizations) {
        PpubsSearchResponse.Patent patent = raw.patent();
        StringBuilder text = new StringBuilder();
        String documentId = text(patent.documentId());
        text.append(granted ? "U.S. patent " : "U.S. patent application publication ")
                .append(documentId == null ? patent.guid() : documentId)
                .append(granted ? ", granted " : ", published ")
                .append(date(patent.datePublished()))
                .append('.');
        LocalDate filed = patent.applicationFilingDate().isEmpty()
                ? null
                : date(patent.applicationFilingDate().get(0));
        if (filed != null) {
            text.append(" Application ");
            if (patent.applicationNumber() != null && !patent.applicationNumber().isBlank()) {
                text.append(patent.applicationNumber().trim()).append(' ');
            }
            text.append("filed ").append(filed);
            LocalDate earliest = patent.relatedApplFilingDate().stream()
                    .map(UsptoNormalizer::date)
                    .filter(d -> d != null && d.isBefore(filed))
                    .min(LocalDate::compareTo)
                    .orElse(null);
            if (earliest != null) {
                text.append(" (earliest related application filed ").append(earliest).append(')');
            }
            text.append('.');
        }
        if (!organizations.isEmpty()) {
            text.append(patent.assigneeName().isEmpty() ? " Applicant: " : " Assignee: ")
                    .append(String.join("; ", organizations.keySet()))
                    .append('.');
        }
        String inventors = text(patent.inventorsShort());
        if (inventors != null) {
            text.append(" Inventors: ").append(inventors).append(inventors.endsWith(".") ? "" : ".");
        }
        List<String> cpc = cpc(patent);
        if (!cpc.isEmpty()) {
            text.append(" CPC: ").append(String.join(", ", cpc)).append('.');
        }
        for (String interest : patent.governmentInterest()) {
            String clean = text(interest);
            if (clean != null) {
                clean = PARAGRAPH_NUMBER.matcher(clean).replaceAll("").trim();
                text.append(" Government interest: ").append(clean).append(clean.endsWith(".") ? "" : ".");
            }
        }
        if (raw.familyMembers() > 1) {
            text.append(" Patent family ")
                    .append(patent.familyIdentifierCur())
                    .append(": ")
                    .append(raw.familyMembers())
                    .append(" documents in the search result (e.g. application and grant); the earliest is shown.");
        }
        if (raw.phrase() != null && !raw.phrase().isBlank()) {
            text.append(" USPTO Patent Public Search matched the phrase \"")
                    .append(raw.phrase())
                    .append("\" in the title or abstract.");
        }
        return text.toString();
    }

    /** Текст без разметки подсветки и сущностей HTML, пробелы схлопнуты; пустое — {@code null}. */
    static String text(String html) {
        if (html == null) {
            return null;
        }
        String withoutTags = HTML_TAG.matcher(html).replaceAll(" ");
        String collapsed = WHITESPACE.matcher(HtmlUtils.htmlUnescape(withoutTags))
                .replaceAll(" ")
                .trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** «2026-09-17T00:00:00Z» → 2026-09-17. */
    static LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
