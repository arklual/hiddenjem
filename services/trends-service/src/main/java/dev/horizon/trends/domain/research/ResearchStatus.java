package dev.horizon.trends.domain.research;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a research request (domain-model doc §4.2).
 *
 * <p>The allowed-transition table is the single place where the process shape is defined; the
 * aggregate consults it instead of scattering {@code if (status == ...)} checks. A table also makes
 * the state machine exhaustively testable — the unit test walks all 7×7 pairs.
 */
public enum ResearchStatus {
    PENDING,
    COLLECTING,
    ANALYZING,
    ASSEMBLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<ResearchStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED);

    private static final Map<ResearchStatus, Set<ResearchStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(COLLECTING, FAILED, CANCELLED),
            COLLECTING, EnumSet.of(ANALYZING, FAILED, CANCELLED),
            ANALYZING, EnumSet.of(ASSEMBLING, FAILED, CANCELLED),
            ASSEMBLING, EnumSet.of(COMPLETED, FAILED, CANCELLED),
            COMPLETED, EnumSet.noneOf(ResearchStatus.class),
            FAILED, EnumSet.noneOf(ResearchStatus.class),
            CANCELLED, EnumSet.noneOf(ResearchStatus.class));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return !isTerminal();
    }

    public boolean canTransitionTo(ResearchStatus next) {
        return next != null && ALLOWED.get(this).contains(next);
    }
}
