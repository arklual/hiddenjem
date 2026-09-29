package dev.horizon.ingestion.domain.port;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.platform.common.util.Guards;

/**
 * Self-description of a connector: identity, what it produces, how fast it may be called and
 * whether it currently has what it needs to run.
 *
 * <p>{@code unavailableReason} is the mechanism behind "a source with no API key reports itself
 * unavailable rather than failing the run" (BR-C7): the collection skips it and lists it in
 * {@code unavailableSources}, and the resulting report is honestly marked partial.
 *
 * <p>{@code switchedOff} отделяет от этого случая другой, внешне такой же: источник выключен
 * настройкой развёртывания. Разница не косметическая. «Недоступен» — это пробел в покрытии, о
 * котором аналитику надо сказать: источник должен был участвовать и не смог. «Выключен» — это
 * решение оператора, и предупреждать о нём в каждом отчёте значит приучить не читать
 * предупреждения. На стенде, где включён только эталонный корпус, оговорка «часть источников была
 * недоступна» висела над каждым отчётом и перечисляла шесть источников, ни один из которых никто не
 * ждал.
 *
 * @param providedClasses classes this connector can yield; a connector serving a mixed corpus (the
 *     fixture golden corpus) lists several
 */
public record SourceDescriptor(
        String id,
        String displayName,
        SourceClass primaryClass,
        Set<SourceClass> providedClasses,
        int requestsPerMinute,
        boolean requiresApiKey,
        boolean credentialsConfigured,
        String unavailableReason,
        boolean switchedOff) {

    public SourceDescriptor {
        id = Guards.requireText(id, "descriptor.id");
        displayName = Guards.requireText(displayName, "descriptor.displayName");
        Guards.requireNonNull(primaryClass, "descriptor.primaryClass");
        providedClasses = providedClasses == null || providedClasses.isEmpty()
                ? Set.of(primaryClass)
                : Collections.unmodifiableSet(EnumSet.copyOf(providedClasses));
        Guards.requireArgument(requestsPerMinute > 0, "requestsPerMinute must be positive");
        unavailableReason = unavailableReason == null || unavailableReason.isBlank() ? null : unavailableReason.trim();
    }

    public static SourceDescriptor available(
            String id, String displayName, SourceClass primaryClass, int requestsPerMinute) {
        return new SourceDescriptor(
                id, displayName, primaryClass, Set.of(primaryClass), requestsPerMinute, false, true, null, false);
    }

    public boolean available() {
        return unavailableReason == null;
    }

    public Optional<String> unavailableReasonOrEmpty() {
        return Optional.ofNullable(unavailableReason);
    }

    public SourceDescriptor unavailable(String reason) {
        return new SourceDescriptor(
                id,
                displayName,
                primaryClass,
                providedClasses,
                requestsPerMinute,
                requiresApiKey,
                credentialsConfigured,
                reason,
                switchedOff);
    }

    /**
     * Источник выключен настройкой развёртывания.
     *
     * <p>Не то же, что {@link #unavailable(String)}: выключенный источник не участвует в сборе и не
     * попадает в перечень недоступных. Его отсутствие — не пробел в покрытии, а состав установки,
     * и сообщать о нём в каждом отчёте нечего.
     */
    public SourceDescriptor switchedOff(String reason) {
        return new SourceDescriptor(
                id,
                displayName,
                primaryClass,
                providedClasses,
                requestsPerMinute,
                requiresApiKey,
                credentialsConfigured,
                reason,
                true);
    }

    public SourceDescriptor withRequestsPerMinute(int perMinute) {
        return new SourceDescriptor(
                id,
                displayName,
                primaryClass,
                providedClasses,
                perMinute,
                requiresApiKey,
                credentialsConfigured,
                unavailableReason,
                switchedOff);
    }
}
