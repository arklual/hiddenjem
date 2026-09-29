package dev.horizon.ingestion.domain.run;

import dev.horizon.platform.common.util.Guards;

/**
 * What a run did, in numbers: {@code fetched} raw records, of which {@code created} became new
 * documents, {@code duplicates} were already known and {@code rejected} could not be normalised.
 *
 * <p>The identity {@code fetched >= created + duplicates + rejected} is not enforced: a source may
 * return a record we filter out before normalisation (outside the window, wrong class), and turning
 * a bookkeeping mismatch into an exception would abort an otherwise good run.
 */
public record RunCounters(int fetched, int created, int duplicates, int rejected) {

    public static final RunCounters ZERO = new RunCounters(0, 0, 0, 0);

    public RunCounters {
        Guards.requireArgument(fetched >= 0, "fetched must not be negative");
        Guards.requireArgument(created >= 0, "created must not be negative");
        Guards.requireArgument(duplicates >= 0, "duplicates must not be negative");
        Guards.requireArgument(rejected >= 0, "rejected must not be negative");
    }

    public RunCounters plus(int moreFetched, int moreCreated, int moreDuplicates, int moreRejected) {
        return new RunCounters(
                fetched + moreFetched, created + moreCreated, duplicates + moreDuplicates, rejected + moreRejected);
    }

    public RunCounters plus(RunCounters other) {
        return plus(other.fetched, other.created, other.duplicates, other.rejected);
    }

    public boolean isEmpty() {
        return fetched == 0 && created == 0 && duplicates == 0 && rejected == 0;
    }
}
