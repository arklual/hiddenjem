package dev.horizon.ingestion.domain.run;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an {@link IngestionRun}.
 *
 * <p>{@code RUNNING → COMPLETED | PARTIAL | FAILED}, and terminal states are final. {@code PARTIAL}
 * is a first-class outcome, not an error: a run that collected documents but hit a circuit breaker
 * or a rate-limit wall on the last pages is still useful, and the report built on it must be marked
 * incomplete rather than discarded (BR-C7, BRULE-8).
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED;

    private static final Set<RunStatus> TERMINAL = EnumSet.of(COMPLETED, PARTIAL, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Pure transition table — exhaustively covered by a unit test. */
    public boolean canTransitionTo(RunStatus next) {
        if (next == null || next == RUNNING) {
            return false;
        }
        return this == RUNNING;
    }
}
