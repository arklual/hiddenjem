package dev.horizon.ingestion.connector.uspto;

import java.util.Locale;
import java.util.regex.Pattern;

import dev.horizon.ingestion.domain.document.OrganizationType;

/**
 * Тип правообладателя патента — по его названию.
 *
 * <p>У PatentsView был числовой код типа правообладателя; Patent Public Search отдаёт в выдаче только
 * имя. Тип нужен показателю «распространение по типам организаций»: патент университета и патент
 * корпорации — разные стадии одной технологии. Поэтому явные академические, государственные и
 * исследовательские названия распознаются, а всё прочее считается компанией: патентует в США
 * прежде всего бизнес, и в записанной выдаче по «neuromorphic computing» так и есть.
 *
 * <p>Порядок проверок важен. {@code Massachusetts Institute of Technology} и KAIST — университеты,
 * хотя в названии «institute»; {@code Purdue Research Foundation} и подобные — фонды передачи
 * технологий при университетах, а не благотворительность. Физическое лицо (в USPTO записывается
 * «Фамилия; Имя») организацией не считается вовсе — как коды 4 и 5 у PatentsView.
 */
public final class Assignees {

    private static final Pattern UNIVERSITY = Pattern.compile("\\b(universit(y|ies|e|é|ä|à|at|ät|a)|college"
            + "|polytechnic|politecnico|school of|ecole|école|institute of technology|advanced institute of science"
            + "|research foundation|regents|trustees)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern GOVERNMENT = Pattern.compile("\\b(united states of america|as represented by"
            + "|secretary of|department of|ministry|government of|national aeronautics|agency for defen[cs]e)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RESEARCH = Pattern.compile("\\b(institut(e|o)?|laborator(y|ies)|research cent(er|re)"
            + "|recherche|fraunhofer|max[- ]planck|commissariat|national lab|sri international|battelle"
            + "|national security, llc|of sandia|a\\*star|agency for science)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NONPROFIT = Pattern.compile("\\b(foundation|association|society|alliance)\\b", Pattern.UNICODE_CHARACTER_CLASS);

    private Assignees() {}

    /** «WU; Yimin» — так USPTO пишет физических лиц среди заявителей. */
    public static boolean isIndividual(String name) {
        return name.contains(";");
    }

    public static OrganizationType typeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (UNIVERSITY.matcher(lower).find()) {
            return OrganizationType.UNIVERSITY;
        }
        if (GOVERNMENT.matcher(lower).find()) {
            return OrganizationType.GOVERNMENT;
        }
        if (RESEARCH.matcher(lower).find()) {
            return OrganizationType.RESEARCH_INSTITUTE;
        }
        if (NONPROFIT.matcher(lower).find()) {
            return OrganizationType.NONPROFIT;
        }
        return OrganizationType.COMPANY;
    }
}
