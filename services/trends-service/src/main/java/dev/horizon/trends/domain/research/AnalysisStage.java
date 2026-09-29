package dev.horizon.trends.domain.research;

/**
 * User-visible pipeline stage with its nominal progress band.
 *
 * <p>Percent bands live here rather than in the UI so that every client — web, API consumer,
 * digest job — reports identical progress for the same state.
 */
public enum AnalysisStage {
    QUEUED(0, 5),
    COLLECTING(5, 40),
    ANALYZING(40, 85),
    ASSEMBLING(85, 98),
    DONE(100, 100);

    private final int startPercent;
    private final int endPercent;

    AnalysisStage(int startPercent, int endPercent) {
        this.startPercent = startPercent;
        this.endPercent = endPercent;
    }

    public int startPercent() {
        return startPercent;
    }

    public int endPercent() {
        return endPercent;
    }

    /** Maps a 0..100 progress reported inside a stage onto the global progress bar. */
    public int globalPercent(int withinStagePercent) {
        int clamped = Math.max(0, Math.min(100, withinStagePercent));
        return startPercent + (int) Math.round((endPercent - startPercent) * (clamped / 100.0));
    }

    public static AnalysisStage forStatus(ResearchStatus status) {
        return switch (status) {
            case PENDING -> QUEUED;
            case COLLECTING -> COLLECTING;
            case ANALYZING -> ANALYZING;
            case ASSEMBLING -> ASSEMBLING;
            case COMPLETED, FAILED, CANCELLED -> DONE;
        };
    }
}
