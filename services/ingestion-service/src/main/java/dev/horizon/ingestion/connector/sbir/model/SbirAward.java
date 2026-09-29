package dev.horizon.ingestion.connector.sbir.model;

import java.time.LocalDate;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Награда SBIR/STTR так, как её показывает страница {@code sbir.gov/awards/<id>}.
 *
 * <p>Поля — ровно то, что напечатано на странице, без толкования: какой датой считать награду и
 * какого типа организация её получила, решает нормализатор.
 *
 * @param startDate «Award Start Date»; у части наград (NIH) страница оставляет её пустой
 * @param awardYear «Award Year» — финансовый год награды, есть всегда
 * @param program {@code SBIR} или {@code STTR}
 * @param phase {@code Phase I}, {@code Phase II}
 * @param researchInstitution партнёр-исследователь STTR; у SBIR его нет
 * @param country страна получателя, ISO alpha-2 — по условию программы всегда США
 */
public record SbirAward(
        String awardId,
        String url,
        String title,
        String company,
        String country,
        String agency,
        String branch,
        String program,
        String phase,
        LocalDate startDate,
        Integer awardYear,
        String abstractText,
        String principalInvestigator,
        String researchInstitution,
        String sourceId,
        Provenance provenance)
        implements RawDocument {

    @Override
    public String externalId() {
        return awardId;
    }

    /** Самая точная дата награды из напечатанных: начало работ, иначе первое января года награды. */
    public LocalDate awardedOn() {
        if (startDate != null) {
            return startDate;
        }
        return awardYear == null ? null : LocalDate.of(awardYear, 1, 1);
    }
}
