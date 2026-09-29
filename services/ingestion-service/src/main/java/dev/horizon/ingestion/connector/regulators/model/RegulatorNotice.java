package dev.horizon.ingestion.connector.regulators.model;

import java.time.LocalDate;
import java.util.List;

import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Запись одной из площадок регуляторов: проект Инновационного центра BIS, фирма песочницы FCA или
 * событие Банка России — уже разобранная, в виде, общем для всех трёх.
 *
 * @param site площадка ({@code bis}, {@code fca}, {@code cbr}) — для журнала и отладки
 * @param publishedOn официальная дата записи; как она получена, у каждой площадки своё и описано у
 *     её разбора
 * @param organizations организации, стоящие за записью: регулятор, партнёры проекта или фирма
 *     песочницы
 * @param venue название площадки в документе
 */
public record RegulatorNotice(
        String site,
        String title,
        String text,
        String url,
        LocalDate publishedOn,
        String language,
        List<Organization> organizations,
        String venue,
        String sourceId,
        String externalId,
        Provenance provenance)
        implements RawDocument {

    public RegulatorNotice {
        organizations = List.copyOf(organizations);
    }

    /** Организация за записью: имя, тип и страна (ISO 3166-1, может отсутствовать). */
    public record Organization(String name, OrganizationType type, String country) {}
}
