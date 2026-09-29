package dev.horizon.ingestion.connector.sbir;

import java.time.LocalDate;
import java.util.Locale;
import java.util.StringJoiner;

import dev.horizon.ingestion.connector.sbir.model.SbirAward;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Награда SBIR/STTR → документ.
 *
 * <p><b>Класс — {@link SourceClass#NEWS}, и это сознательный выбор из закрытого перечня.</b>
 * Грант не патент: записать его в {@code PATENT} значило бы надуть долю патентов в индикаторе
 * влияния и выдать награду за «патентное основание» темы. Не аналитический отчёт и не статья. Из
 * оставшихся {@code NEWS} — единственный класс, который не утверждает о документе неправды:
 * публичное сообщение о событии (государство профинансировало прототип). Доверенность при этом не
 * теряется: модуль доверенности смотрит сперва на хост, и {@code www.sbir.gov} как домен
 * {@code .gov} получает высокий уровень, а с ним — право быть основанием темы, а не только
 * первичным индикатором.
 *
 * <p><b>Получатель — всегда компания.</b> SBIR и STTR выдаются только малому бизнесу; это условие
 * программы, а не догадка по названию. Партнёр-исследователь STTR (университет, лаборатория)
 * записывается вторым участником со своей организацией: правило «две независимые организации»
 * должно видеть, что за прототипом стоят и бизнес, и наука.
 *
 * <p><b>Фаза — в издании.</b> «SBIR Phase I» — ровно та метка ранней стадии, ради которой источник
 * заведён (прототип профинансирован, продукта нет); она должна быть видна аналитику рядом со
 * ссылкой, а не только в тексте аннотации.
 */
public class SbirNormalizer implements DocumentNormalizer<SbirAward> {

    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(SbirAward award) {
        if (award.title() == null) {
            throw new IllegalArgumentException("SBIR award without a title: " + award.url());
        }
        if (award.company() == null) {
            throw new IllegalArgumentException("SBIR award without an awardee: " + award.url());
        }
        LocalDate awardedOn = award.awardedOn();
        if (awardedOn == null) {
            throw new IllegalArgumentException("SBIR award without a date: " + award.url());
        }
        var builder = Document.builder()
                .externalRef(award.sourceId(), award.awardId())
                .sourceClass(SourceClass.NEWS)
                .title(award.title())
                .abstractText(truncate(award.abstractText()))
                .language("en")
                .publishedOn(awardedOn)
                .url(award.url())
                .venue(new Venue(venueName(award), "GRANT_REGISTRY", null))
                .provenance(award.provenance());
        String investigator = award.principalInvestigator();
        builder.author(new Author(
                investigator == null ? award.company() : investigator,
                null,
                award.company(),
                OrganizationType.COMPANY,
                award.country()));
        if (award.researchInstitution() != null) {
            builder.author(new Author(
                    award.researchInstitution(),
                    null,
                    award.researchInstitution(),
                    institutionType(award.researchInstitution()),
                    award.country()));
        }
        return builder.build();
    }

    /** «SBIR.gov — STTR Phase I, DOW / USAF»: программа, фаза и заказчик одной строкой. */
    static String venueName(SbirAward award) {
        StringJoiner label = new StringJoiner(" ");
        if (award.program() != null) {
            label.add(award.program());
        }
        if (award.phase() != null) {
            label.add(award.phase());
        }
        StringBuilder name = new StringBuilder("SBIR.gov");
        if (label.length() > 0) {
            name.append(" — ").append(label);
        }
        if (award.agency() != null) {
            name.append(", ").append(award.agency());
            if (award.branch() != null) {
                name.append(" / ").append(award.branch());
            }
        }
        return name.toString();
    }

    /**
     * Партнёр STTR — университет или исследовательская организация: иных программа не допускает.
     * Различаются по названию; национальные лаборатории и фонды при университетах — второе.
     */
    static OrganizationType institutionType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("universit")
                || lower.contains("college")
                || lower.contains("institute of technology")
                || lower.contains("polytechnic")) {
            return OrganizationType.UNIVERSITY;
        }
        return OrganizationType.RESEARCH_INSTITUTE;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
