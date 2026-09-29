package dev.horizon.trends.domain.research;

import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;

/** Typed identifier — prevents accidentally passing a report id where a request id is expected. */
public record ResearchRequestId(UUID value) {

    public ResearchRequestId {
        Guards.requireNonNull(value, "researchRequestId");
    }

    public static ResearchRequestId generate() {
        return new ResearchRequestId(Uuid7.randomUuid7());
    }

    public static ResearchRequestId of(String value) {
        return new ResearchRequestId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
