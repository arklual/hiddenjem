package dev.horizon.trends.adapter.web.dto;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;

/**
 * Wire form of {@code AnalysisParameters} (OpenAPI {@code #/components/schemas/AnalysisParameters}).
 *
 * <p>Every field is a boxed type because every field is optional on the wire: the contract declares
 * defaults, and a primitive would silently turn "not supplied" into {@code 0}, which is a different
 * — and invalid — request. The defaults are applied in {@link #toDomain()} from the domain's own
 * constants so the API and the aggregate can never disagree about what "default" means.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisParametersDto(
        @Min(AnalysisParameters.MIN_TOP_N) @Max(AnalysisParameters.MAX_TOP_N) Integer topN,
        @Min(AnalysisParameters.MIN_YEARS_WINDOW) @Max(AnalysisParameters.MAX_YEARS_WINDOW) Integer yearsWindow,
        List<SourceClass> sourceClasses,
        @DecimalMin("0.0") @DecimalMax("1.0") Double minConfidence,
        Boolean includeMature,
        /**
         * Режим анализа: {@code fast} | {@code quality}.
         *
         * <p>Строка, а не {@code AnalysisMode}: перечисление Jackson разобрал бы сам, но ответ на
         * опечатку пришёл бы в форме «не удалось прочитать тело» — без имени поля и без перечня
         * допустимых значений. Разбор принадлежит домену ({@link AnalysisMode#of}), и отказ его
         * словами называет и то, и другое.
         *
         * <p>Ни движка, ни профиля методологии здесь больше нет: движок один, а профиль всегда
         * берётся по умолчанию. Клиент, приславший их по старой памяти, получит тот же расчёт, —
         * незнакомые поля тела не отвергаются.
         */
        String mode) {

    /** Null-safe: an absent {@code parameters} object means "all defaults". */
    public static AnalysisParameters toDomain(AnalysisParametersDto dto) {
        return dto == null ? AnalysisParameters.defaults(null) : dto.toDomain();
    }

    public AnalysisParameters toDomain() {
        Set<SourceClass> classes =
                sourceClasses == null || sourceClasses.isEmpty() ? Set.of() : EnumSet.copyOf(sourceClasses);
        return new AnalysisParameters(
                topN == null ? AnalysisParameters.DEFAULT_TOP_N : topN,
                yearsWindow == null ? AnalysisParameters.DEFAULT_YEARS_WINDOW : yearsWindow,
                classes,
                minConfidence == null ? 0.0 : minConfidence,
                includeMature != null && includeMature,
                // Профиль назначает приём запроса — всегда умолчание; ни тело, ни этот слой его не выбирают.
                null,
                AnalysisMode.of(mode));
    }

    public static AnalysisParametersDto from(AnalysisParameters parameters) {
        return new AnalysisParametersDto(
                parameters.topN(),
                parameters.yearsWindow(),
                parameters.sourceClasses().stream().sorted().toList(),
                parameters.minConfidence(),
                parameters.includeMature(),
                parameters.mode().wireName());
    }
}
