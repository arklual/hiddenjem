package dev.horizon.ingestion.connector.ietf;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.ietf.model.IetfResponses;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для IETF Datatracker: черновик стандарта → документ.
 *
 * <p><b>Дата — первая ревизия, а не {@code time}.</b> Поле {@code time} в карточке черновика —
 * последнее изменение записи: новая ревизия, смена состояния, даже автоматическое истечение срока.
 * Черновик 2019 года, истёкший в прошлом месяце, по нему выглядел бы свежим. Появление технологии
 * в протоколах датируется ревизией {@code 00}: её коннектор получает одним пакетным запросом на
 * страницу. Черновик без известной первой ревизии отвергается — выдумывать дату нельзя, а
 * отвергнутый попадает в счётчик прогона.
 *
 * <p><b>Площадка говорит о зрелости.</b> Имя {@code draft-ietf-<группа>-…} означает, что черновик
 * принят рабочей группой IETF, — это уже не идея одного автора, а работа над стандартом.
 * Индивидуальный черновик ({@code draft-<автор>-…}) — самая ранняя стадия. Разница уходит в
 * название площадки, чтобы аналитик её видел.
 *
 * <p><b>Организации — аффилиации авторов.</b> В IETF это почти всегда компании-производители
 * (Cisco, Nokia, Google), поэтому название без признаков университета, института или ведомства
 * считается компанией. «Independent» и подобное — не организация.
 */
public class IetfNormalizer implements DocumentNormalizer<IetfResponses.Raw> {

    private static final String DOC_URL = "https://datatracker.ietf.org/doc/";
    private static final Pattern WORKING_GROUP = Pattern.compile("^draft-ietf-([a-z0-9]+)-");
    private static final Pattern UNIVERSITY = Pattern.compile(
            "\\b(university|universit[äéà]t?|universidad|université|universiteit|college|école|ecole|"
                    + "polytechnic|politecnico|eth zurich|kaist|tu [a-z]+|virginia tech|georgia tech|caltech)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GOVERNMENT = Pattern.compile(
            "\\b(nist|nsa|national security agency|ministry|government|federal office|bundesamt|bsi|anssi|"
                    + "cisa|department of|agency|national cyber security cent(er|re)|ncsc)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUTE = Pattern.compile(
            "\\b(institute|institut|inria|fraunhofer|cnrs|laborator(y|ies)|research cent(er|re)|academy)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_AN_ORGANIZATION = Pattern.compile(
            "^(independent|individual|self|self-employed|none|n/a|retired|consultant|unaffiliated)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** «United Kingdom», «Germany» → ISO-код; Datatracker хранит страну свободным текстом. */
    private static final Map<String, String> COUNTRIES = countries();

    @Override
    public Document normalize(IetfResponses.Raw raw) {
        IetfResponses.Draft draft = raw.draft();
        if (draft.name() == null || draft.name().isBlank()) {
            throw new IllegalArgumentException("IETF draft without a name");
        }
        if (draft.title() == null || draft.title().isBlank()) {
            throw new IllegalArgumentException("IETF draft without a title: " + draft.name());
        }
        if (raw.firstRevision() == null) {
            throw new IllegalArgumentException("IETF draft without a known first revision: " + draft.name());
        }
        String name = draft.name().trim();
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.STANDARD)
                .title(collapse(draft.title()))
                .abstractText(draft.abstractText() == null ? null : collapse(draft.abstractText()))
                .language("en")
                .publishedOn(raw.firstRevision())
                .url(DOC_URL + name + "/")
                .venue(new Venue(venueOf(name), "STANDARDS_BODY", null))
                .metrics(new DocumentMetrics(null, null, null, metricsOf(draft)))
                .provenance(raw.provenance());
        for (IetfResponses.DraftAuthor author : raw.authors()) {
            if (author.name() == null || author.name().isBlank()) {
                continue;
            }
            String organization = organizationOf(author.affiliation());
            builder.author(new Author(
                    author.name(),
                    null,
                    organization,
                    organization == null ? null : typeOf(organization),
                    countryOf(author.country())));
        }
        return builder.build();
    }

    static String venueOf(String name) {
        Matcher matcher = WORKING_GROUP.matcher(name);
        return matcher.find() ? "IETF, черновик рабочей группы " + matcher.group(1) : "IETF, индивидуальный черновик";
    }

    static String organizationOf(String affiliation) {
        if (affiliation == null || affiliation.isBlank()) {
            return null;
        }
        String value = affiliation.trim();
        return NOT_AN_ORGANIZATION.matcher(value).matches() ? null : value;
    }

    static OrganizationType typeOf(String organization) {
        if (UNIVERSITY.matcher(organization).find()) {
            return OrganizationType.UNIVERSITY;
        }
        if (GOVERNMENT.matcher(organization).find()) {
            return OrganizationType.GOVERNMENT;
        }
        if (INSTITUTE.matcher(organization).find()) {
            return OrganizationType.RESEARCH_INSTITUTE;
        }
        return OrganizationType.COMPANY;
    }

    static String countryOf(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return COUNTRIES.get(country.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Number> metricsOf(IetfResponses.Draft draft) {
        Map<String, Number> extra = new HashMap<>();
        if (draft.rev() != null && draft.rev().matches("\\d{1,3}")) {
            extra.put("revision", Integer.parseInt(draft.rev()));
        }
        if (draft.pages() != null) {
            extra.put("pages", draft.pages());
        }
        return extra;
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> countries() {
        Map<String, String> byName = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            byName.put(Locale.of("", code).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT), code);
            byName.put(code.toLowerCase(Locale.ROOT), code);
        }
        byName.put("usa", "US");
        byName.put("united states of america", "US");
        byName.put("uk", "GB");
        byName.put("south korea", "KR");
        byName.put("korea", "KR");
        byName.put("russia", "RU");
        byName.put("czech republic", "CZ");
        byName.put("the netherlands", "NL");
        return Map.copyOf(byName);
    }
}
