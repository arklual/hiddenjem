package dev.horizon.ingestion.connector.edgar;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.edgar.model.EdgarSearchResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для SEC EDGAR: карточка поданного файла → документ.
 *
 * <p><b>Текст документа — только то, что известно наверняка.</b> Поиск отдаёт карточку без самого
 * текста подачи: форма, заявитель, место, дата, номер. Аннотация собирается из этих полей и из
 * смысла формы по правилам SEC — «Form D — уведомление о частном размещении по Regulation D»,
 * «S-1 — регистрация выпуска бумаг, обычно перед IPO», — плюс факт совпадения: полнотекстовый
 * поиск нашёл формулировку в тексте подачи. Сумм раунда и пересказа проспекта тут нет: их в
 * ответе нет, и выдумывать их нельзя.
 *
 * <p><b>Организация — заявитель, тип — компания.</b> Подавать Form D и S-1 может только эмитент
 * ценных бумаг, то есть компания или фонд. Из имени убираются тикеры и {@code (CIK …)}, которые
 * EDGAR приклеивает к названию: иначе одна и та же компания считалась бы разными организациями.
 *
 * <p><b>Адрес — сам файл в архиве</b> {@code /Archives/edgar/data/…}: это первоисточник, и
 * {@code robots.txt} SEC этот путь разрешает. Для Form D, которая подаётся XML-анкетой, адрес ведёт
 * на её официальное HTML-представление ({@code xslFormDX01/…}), чтобы аналитик открыл читаемую
 * страницу, а не разметку.
 */
public class EdgarNormalizer implements DocumentNormalizer<EdgarSearchResponse.Raw> {

    private static final String ARCHIVE = "https://www.sec.gov/Archives/edgar/data/";
    /** «Quantum Computing Inc.  (QUBT)  (CIK 0001758009)» — хвост из тикеров и CIK. */
    private static final Pattern CIK_SUFFIX = Pattern.compile("\\s*\\(CIK\\s*\\d+\\)\\s*$");

    private static final Pattern TICKER_SUFFIX = Pattern.compile("\\s*\\([A-Z0-9.\\-]+(,\\s*[A-Z0-9.\\-]+)*\\)\\s*$");
    /** Коды штатов EDGAR: у иностранных заявителей там коды стран EDGAR («K3», «A1»), не ISO. */
    private static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY",
            "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
            "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR");

    @Override
    public Document normalize(EdgarSearchResponse.Raw raw) {
        EdgarSearchResponse.Hit hit = raw.hit();
        EdgarSearchResponse.Filing filing = hit.source();
        if (filing == null || filing.form() == null || filing.form().isBlank()) {
            throw new IllegalArgumentException("EDGAR hit without a form: " + hit.id());
        }
        if (filing.fileDate() == null || filing.fileDate().isBlank()) {
            throw new IllegalArgumentException("EDGAR hit without a filing date: " + hit.id());
        }
        List<String> companies = companies(filing);
        if (companies.isEmpty()) {
            throw new IllegalArgumentException("EDGAR hit without a filer: " + hit.id());
        }
        String form = filing.form().trim();
        String url = documentUrl(hit);
        if (url == null) {
            throw new IllegalArgumentException("EDGAR hit without an archive path: " + hit.id());
        }
        String country = country(filing);

        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(formLabel(form) + " — " + String.join("; ", companies))
                .abstractText(abstractOf(filing, form, companies, raw.phrase()))
                .language("en")
                .publishedOn(LocalDate.parse(filing.fileDate().trim()))
                .url(url)
                .venue(new Venue("SEC EDGAR", "REGULATOR", null))
                .metrics(new DocumentMetrics(null, null, null, Map.of()))
                .provenance(raw.provenance());
        for (String company : companies) {
            builder.author(new Author(company, null, company, OrganizationType.COMPANY, country));
        }
        return builder.build();
    }

    /** Адрес файла в архиве: CIK без ведущих нулей, номер подачи без дефисов, имя файла из {@code _id}. */
    static String documentUrl(EdgarSearchResponse.Hit hit) {
        EdgarSearchResponse.Filing filing = hit.source();
        if (hit.id() == null || !hit.id().contains(":") || filing.ciks().isEmpty()) {
            return null;
        }
        String adsh = hit.id().substring(0, hit.id().indexOf(':'));
        String file = hit.id().substring(hit.id().indexOf(':') + 1);
        String cik = filing.ciks().get(0).replaceFirst("^0+", "");
        if (adsh.isBlank() || file.isBlank() || cik.isBlank()) {
            return null;
        }
        String folder = ARCHIVE + cik + "/" + adsh.replace("-", "") + "/";
        if (filing.xsl() != null && !filing.xsl().isBlank() && file.endsWith(".xml")) {
            return folder + filing.xsl().trim() + "/" + file;
        }
        return folder + file;
    }

    static List<String> companies(EdgarSearchResponse.Filing filing) {
        Set<String> names = new LinkedHashSet<>();
        for (String display : filing.displayNames()) {
            if (display == null) {
                continue;
            }
            String name = CIK_SUFFIX.matcher(display.trim()).replaceAll("");
            name = TICKER_SUFFIX.matcher(name).replaceAll("");
            name = name.replaceAll("\\s{2,}", " ").trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String country(EdgarSearchResponse.Filing filing) {
        if (filing.bizStates().isEmpty()) {
            return null;
        }
        return US_STATES.contains(filing.bizStates().get(0).trim()) ? "US" : null;
    }

    private static String formLabel(String form) {
        return form.startsWith("D") ? "Form " + form : form;
    }

    private static String abstractOf(
            EdgarSearchResponse.Filing filing, String form, List<String> companies, String phrase) {
        StringBuilder text = new StringBuilder();
        text.append("SEC filing ")
                .append(formLabel(form))
                .append(" filed on ")
                .append(filing.fileDate().trim())
                .append(". ")
                .append(meaning(form))
                .append(" Filer: ")
                .append(String.join("; ", companies));
        Map<String, String> details = new LinkedHashMap<>();
        if (!filing.bizLocations().isEmpty() && !filing.bizLocations().get(0).isBlank()) {
            details.put("location", filing.bizLocations().get(0).trim());
        }
        if (!filing.sics().isEmpty() && !filing.sics().get(0).isBlank()) {
            details.put("SIC", filing.sics().get(0).trim());
        }
        if (!details.isEmpty()) {
            text.append(" (");
            text.append(String.join(
                    "; ",
                    details.entrySet().stream()
                            .map(e -> e.getKey() + " " + e.getValue())
                            .toList()));
            text.append(")");
        }
        text.append('.');
        if (filing.fileDescription() != null && !filing.fileDescription().isBlank()) {
            text.append(" Document: ").append(filing.fileDescription().trim()).append('.');
        }
        if (phrase != null && !phrase.isBlank()) {
            text.append(" EDGAR full-text search matched the phrase \"")
                    .append(phrase)
                    .append("\" in this filing.");
        }
        return text.toString();
    }

    /** Смысл формы по правилам SEC — ровно то, что подача сама по себе означает. */
    private static String meaning(String form) {
        if (form.startsWith("D")) {
            return form.endsWith("/A")
                    ? "Amendment to a notice of an exempt offering of securities under Regulation D"
                            + " (a private funding round)."
                    : "Notice of an exempt offering of securities under Regulation D (a private funding round).";
        }
        if (form.startsWith("S-1")) {
            return form.endsWith("/A")
                    ? "Amendment to a registration statement for a public offering of securities (typically an IPO)."
                    : "Registration statement for a public offering of securities (typically an IPO).";
        }
        return "Filing with the U.S. Securities and Exchange Commission.";
    }
}
