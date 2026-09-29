package dev.horizon.ingestion.connector.nsf;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

import dev.horizon.ingestion.connector.nsf.model.NsfResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Награда NSF → документ.
 *
 * <p><b>Дата — решение о награде ({@code date}), а не начало работ.</b> Начало нередко назначено
 * на полгода вперёд: награда, объявленная в августе, начинается в январе. Сигнал о том, что
 * государство сочло направление достойным денег, появляется в день решения — и по этой же дате API
 * отбирает окно.
 *
 * <p><b>Класс — {@link SourceClass#NEWS}</b> по тем же причинам, что у SBIR: грант не патент, не
 * статья и не отчёт; а {@code www.nsf.gov} как домен {@code .gov} модуль доверенности оценивает
 * высоко и без оглядки на класс.
 *
 * <p><b>Тип организации.</b> Малый бизнес получает от NSF деньги только по SBIR/STTR, и программа
 * называет его без догадок. Для остальных тип выводится по названию; названию, которое не говорит
 * ничего определённого, тип не присваивается — пропущенный тип виден, а неверный тихо искажает
 * индикатор диффузии по типам организаций.
 */
public class NsfNormalizer implements DocumentNormalizer<NsfResponse.Raw> {

    static final String AWARD_URL = "https://www.nsf.gov/awardsearch/show-award/?AWD_ID=";
    private static final DateTimeFormatter NSF_DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMPANY_SUFFIX =
            Pattern.compile("\\b(inc|llc|l\\.l\\.c|corp|corporation|ltd|co|company|technologies|pbc)\\b\\.?");
    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(NsfResponse.Raw raw) {
        NsfResponse.Award award = raw.award();
        String title = clean(award.title());
        if (title == null) {
            throw new IllegalArgumentException("NSF award without a title: " + award.id());
        }
        LocalDate awardedOn = awardedOn(award);
        if (awardedOn == null) {
            throw new IllegalArgumentException("NSF award without a date: " + award.id());
        }
        String organization = clean(award.awardeeName());
        OrganizationType type = organizationType(organization, award.fundProgramName());
        String program = clean(award.fundProgramName());
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(title)
                .abstractText(truncate(clean(award.abstractText())))
                .language("en")
                .publishedOn(awardedOn)
                .url(AWARD_URL + award.id())
                .venue(new Venue(program == null ? "NSF" : "NSF — " + program, "GRANT_REGISTRY", null))
                .provenance(raw.provenance());
        String investigator = investigator(award);
        String name = investigator != null ? investigator : organization != null ? organization : "NSF";
        builder.author(new Author(name, null, organization, type, award.awardeeCountryCode()));
        return builder.build();
    }

    static LocalDate awardedOn(NsfResponse.Award award) {
        LocalDate date = parse(award.date());
        return date != null ? date : parse(award.startDate());
    }

    static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), NSF_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Тип получателя: программа SBIR/STTR — компания; иначе по названию, от самого надёжного
     * признака к слабому. Университет проверяется раньше суффикса «Inc»: исследовательские фонды при
     * университетах зарегистрированы как корпорации («University of Kansas Center for Research Inc»).
     */
    static OrganizationType organizationType(String awardee, String program) {
        if (program != null) {
            String upper = program.toUpperCase(Locale.ROOT);
            if (upper.contains("SBIR") || upper.contains("STTR")) {
                return OrganizationType.COMPANY;
            }
        }
        if (awardee == null) {
            return null;
        }
        String name = awardee.toLowerCase(Locale.ROOT);
        if (name.contains("universit")
                || name.contains("college")
                || name.contains("institute of technology")
                || name.contains("polytechnic")
                || name.contains("school of")) {
            return OrganizationType.UNIVERSITY;
        }
        if (COMPANY_SUFFIX.matcher(name).find()) {
            return OrganizationType.COMPANY;
        }
        if (name.contains("laborator")
                || name.contains("institute")
                || name.contains("institution")
                || name.contains("observatory")
                || name.contains("research center")) {
            return OrganizationType.RESEARCH_INSTITUTE;
        }
        if (name.contains("foundation")
                || name.contains("society")
                || name.contains("association")
                || name.contains("museum")
                || name.contains("academy")
                || name.contains("council")) {
            return OrganizationType.NONPROFIT;
        }
        return null;
    }

    private static String investigator(NsfResponse.Award award) {
        String first = clean(award.piFirstName());
        String last = clean(award.piLastName());
        if (last == null) {
            return null;
        }
        return first == null ? last : first + " " + last;
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = HtmlUtils.htmlUnescape(HTML_TAG.matcher(value).replaceAll(" "));
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
