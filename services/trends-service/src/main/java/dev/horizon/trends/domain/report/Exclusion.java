package dev.horizon.trends.domain.report;

import java.util.List;

import dev.horizon.platform.common.util.Guards;

/**
 * Почему кандидаты не попали в отчёт: причина словами, число и несколько имён.
 *
 * <p>Требование ТЗ, а не улучшение подачи: «в веб-интерфейсе должны отображаться … причины
 * исключения зрелых технологий или нерелевантных кандидатов», и отдельно — «должна быть
 * продемонстрирована логика исключения зрелых трендов, массово внедренных технологий, отраслевых
 * стандартов, маркетингового хайпа и информационного шума».
 *
 * <p>Примеры идут вместе со счётчиком, и это главное в записи. Число без имён проверить нельзя:
 * «отсеяно 2400 кандидатов» одинаково читается и там, где отсеяли мусор, и там, где отсеяли тему,
 * которую аналитик искал. Именно так однажды выяснилось, что порог релевантности неработоспособен:
 * агрегированные счётчики сообщали объём и молчали о том, что именно ушло.
 */
public record Exclusion(String code, String reason, int count, List<String> examples) {

    public Exclusion {
        Guards.requireText(code, "exclusion.code");
        Guards.requireText(reason, "exclusion.reason");
        Guards.requireArgument(count >= 1, "exclusion.count must be positive");
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
