package dev.horizon.trends.adapter.persistence;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;

/**
 * Wire shape of the {@code parameters} jsonb column.
 *
 * <p>A dedicated record rather than serialising {@link AnalysisParameters} directly: the domain type
 * holds a {@code Set<SourceClass>} whose JSON representation would be an unordered array, and a
 * stored document must be stable and re-readable after the enum changes. Unknown class names are
 * dropped on read for exactly that reason.
 *
 * <p>Незнакомые ключи пропускаются явно, а не по настройке общего {@code ObjectMapper}: строки,
 * записанные до вывода движка методологии, несут {@code engine}, и сохранённое направление обязано
 * читаться и после того, как поле ушло из модели.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredParameters(
        int topN,
        int yearsWindow,
        List<String> sourceClasses,
        double minConfidence,
        boolean includeMature,
        UUID methodologyProfileId,
        String mode) {

    public static StoredParameters from(AnalysisParameters parameters) {
        return new StoredParameters(
                parameters.topN(),
                parameters.yearsWindow(),
                parameters.sourceClasses().stream().map(Enum::name).sorted().toList(),
                parameters.minConfidence(),
                parameters.includeMature(),
                parameters.methodologyProfileId(),
                parameters.mode().wireName());
    }

    public AnalysisParameters toDomain() {
        Set<SourceClass> classes = EnumSet.noneOf(SourceClass.class);
        if (sourceClasses != null) {
            for (String name : sourceClasses) {
                try {
                    classes.add(SourceClass.valueOf(name));
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    // Forward compatibility: a retired source class must not break an old row.
                }
            }
        }
        // Строка, записанная до появления режимов, поля не несёт — и это «быстрый», а не повод
        // сделать сохранённое направление нечитаемым. Сохранённый `engine` не читается вовсе:
        // движок один, и `methodology` в старой строке означает для нового запуска то же, что и
        // отсутствие, — считать сигналами.
        return new AnalysisParameters(
                topN,
                yearsWindow,
                classes,
                minConfidence,
                includeMature,
                methodologyProfileId,
                AnalysisMode.stored(mode));
    }
}
