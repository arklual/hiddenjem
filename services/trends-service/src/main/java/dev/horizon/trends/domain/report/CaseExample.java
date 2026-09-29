package dev.horizon.trends.domain.report;

import dev.horizon.platform.common.util.Guards;

/** A concrete organisation or study driving the trend (BR-A4). */
public record CaseExample(
        String organization,
        OrganizationType organizationType,
        String country,
        String summary,
        int evidenceIndex,
        Basis basis) {

    public CaseExample {
        Guards.requireLength(organization, "caseExample.organization", 1, 300);
        Guards.requireArgument(evidenceIndex >= 0, "caseExample.evidenceIndex must be non-negative");
    }

    public enum OrganizationType {
        COMPANY,
        UNIVERSITY,
        RESEARCH_INSTITUTE,
        GOVERNMENT,
        NONPROFIT
    }

    /**
     * Какое из трёх правил методологии §8 подобрало пример.
     *
     * <p>Перечисление повторяет правила один в один, а не пересказывает их: патент означает, что
     * организация закрепила право на применение, публикация — что она над темой работает.
     * Внедрение из второго не следует, и разница обязана доходить до читателя. Замер по эталонному
     * корпусу: из 90 тем 29 держатся на патенте, 61 — на публикации, 21 из них на препринте.
     *
     * <p>{@code null} возможен и означает отчёт, сохранённый до появления поля.
     */
    public enum Basis {
        PATENT,
        CORPORATE_PUBLICATION,
        ACADEMIC_GROUP
    }
}
