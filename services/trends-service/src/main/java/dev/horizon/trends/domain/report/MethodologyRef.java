package dev.horizon.trends.domain.report;

import java.util.UUID;

import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.research.AnalysisEngine;
import dev.horizon.trends.domain.research.AnalysisMode;

/**
 * Which methodology produced a report.
 *
 * <p>Stored with every report so that results remain interpretable and reproducible after the
 * formulas evolve (BR-B6). Without this, a report from six months ago would be an unfalsifiable
 * number.
 *
 * @param engine каким движком посчитан отчёт ({@code methodology} | {@code signals}). Строка, а не
 *     перечисление, и в этом вся разница между двумя сторонами пути: на запросе движок выбираем мы
 *     и незнакомое имя обязаны отвергнуть, а здесь движок называет себя сам — и сервис, оказавшийся
 *     старше движка, не вправе ронять чтение готового отчёта из-за имени, которого ещё не знает.
 *     Пустое значение — «методология»: так посчитано всё, что записано до появления второго движка.
 * @param mode в каком режиме считан отчёт ({@code fast} | {@code quality}). Рядом с движком, потому что
 *     отвечает на тот же вопрос — «как получено», — и так же не восстанавливается задним числом.
 *     Пустое значение — {@code fast}: до появления режимов всё считалось в срок быстрого.
 */
public record MethodologyRef(String version, UUID profileId, String aggregator, String engine, String mode) {
    public MethodologyRef {
        Guards.requireText(version, "methodologyVersion");
        Guards.requireNonNull(profileId, "methodologyProfileId");
        Guards.requireText(aggregator, "scoreAggregator");
        engine = engine == null || engine.isBlank() ? AnalysisEngine.UNLABELLED_REPORT.wireName() : engine;
        mode = AnalysisMode.stored(mode).wireName();
    }

    /** Отчёт, о режиме которого сказать нечего, — то есть считанный быстро. */
    public MethodologyRef(String version, UUID profileId, String aggregator, String engine) {
        this(version, profileId, aggregator, engine, null);
    }

    /** Отчёт, о движке которого сказать нечего, — то есть посчитанный методологией. */
    public MethodologyRef(String version, UUID profileId, String aggregator) {
        this(version, profileId, aggregator, null, null);
    }
}
