package dev.horizon.ingestion.connector.newswire;

import java.util.Locale;
import java.util.regex.Pattern;

import dev.horizon.ingestion.domain.document.OrganizationType;

/**
 * Тип организации, выпустившей релиз, — по её названию.
 *
 * <p>Площадки тип не сообщают, а релизы выпускают не только компании: в записанной выдаче PR
 * Newswire по «neuromorphic computing» есть Dongguk University, VTT и EDGE AI FOUNDATION. Записать
 * университет компанией значит исказить показатель «распространение по типам организаций» —
 * поэтому явные академические, государственные и некоммерческие названия распознаются, а всё
 * остальное считается компанией: релиз на платной площадке распространения — инструмент бизнеса,
 * и компания здесь — правило, а не догадка.
 */
final class Issuers {

    private static final Pattern UNIVERSITY =
            Pattern.compile("\\b(university|universit[äée]|college|school of|polytechnic|academy)\\b");
    private static final Pattern RESEARCH =
            Pattern.compile("\\b(institute|institut|laborator(y|ies)|research cent(er|re)|national lab|vtt)\\b");
    private static final Pattern GOVERNMENT = Pattern.compile("\\b(ministry|department of|government of|city of)\\b");
    private static final Pattern NONPROFIT =
            Pattern.compile("\\b(foundation|association|alliance|consortium|society|council|federation)\\b");

    private Issuers() {}

    static OrganizationType typeOf(String issuer) {
        String name = issuer.toLowerCase(Locale.ROOT);
        if (UNIVERSITY.matcher(name).find()) {
            return OrganizationType.UNIVERSITY;
        }
        if (RESEARCH.matcher(name).find()) {
            return OrganizationType.RESEARCH_INSTITUTE;
        }
        if (GOVERNMENT.matcher(name).find()) {
            return OrganizationType.GOVERNMENT;
        }
        if (NONPROFIT.matcher(name).find()) {
            return OrganizationType.NONPROFIT;
        }
        return OrganizationType.COMPANY;
    }
}
