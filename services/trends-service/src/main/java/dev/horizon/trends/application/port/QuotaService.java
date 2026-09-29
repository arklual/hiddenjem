package dev.horizon.trends.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Per-user and per-organisation request quotas (FR-02.6, BR-D4). */
public interface QuotaService {

    /**
     * @throws dev.horizon.platform.common.error.HorizonException with {@code quota-exceeded} when the
     *     caller has no budget left
     */
    void checkAndConsume(UUID userId, UUID organizationId);

    /** Returns the consumed budget after a request was rejected for other reasons. */
    /**
     * Give back one request charged at {@code chargedAt}.
     *
     * <p>The instant is a parameter rather than "now" because the counter is bucketed by the hour:
     * a failure that straddles the boundary would otherwise credit a bucket that was never charged,
     * leave the charged one standing, and do it silently — the exact outcome this method exists to
     * prevent.
     */
    void refund(UUID userId, UUID organizationId, Instant chargedAt);

    /**
     * What is left of the hourly budget (BR-A49).
     *
     * <p>An estimate, not a promise: the window slides and the organisation's half is shared, so a
     * colleague may spend a slot between the answer and the next click. The interface says
     * "примерно" for that reason.
     *
     * @return {@link Optional#empty()} when the counter cannot be read. Not zero: the counter is
     *     fail-open, work is not blocked, and reporting "нет бюджета" would be the interface saying
     *     the opposite of what the system does.
     */
    Optional<QuotaBudget> remaining(UUID userId, UUID organizationId);

    /**
     * @param userRemaining slots left of this analyst's own hourly allowance
     * @param organizationRemaining slots left of the organisation's, shared with colleagues
     */
    record QuotaBudget(int userRemaining, int userLimit, int organizationRemaining, int organizationLimit) {

        /** The one that runs out first is the one that stops the analyst (P4). */
        public int effectiveRemaining() {
            return Math.min(userRemaining, organizationRemaining);
        }
    }
}
