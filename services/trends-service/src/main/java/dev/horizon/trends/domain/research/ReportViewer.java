package dev.horizon.trends.domain.research;

import java.util.UUID;

import dev.horizon.platform.common.util.Guards;

/**
 * Who is asking to read a report (BR-A42).
 *
 * <p>One value rather than three arguments. The access rule is checked in half a dozen places, and
 * spreading it over a {@code UUID} and a {@code boolean} meant every call site was an opportunity to
 * pass the wrong identifier in the right position — a mistake no compiler would catch. Adding the
 * organisation as a third loose argument would have tripled that surface.
 *
 * @param organizationId may be absent: the token does not require one, and a request without an
 *     organisation belongs to nobody but its author. "No organisation" must never widen into
 *     "everyone's".
 */
public record ReportViewer(UUID userId, UUID organizationId, boolean administrator) {

    public ReportViewer {
        Guards.requireNonNull(userId, "viewer.userId");
    }

    /**
     * A reader with no organisation — sees only their own work.
     *
     * <p>For tests and for callers that genuinely have no organisation. Production code builds the
     * viewer from the authenticated principal via {@code ReportViewers.of}; using this factory there
     * would narrow the rule silently.
     */
    public static ReportViewer of(UUID userId) {
        return new ReportViewer(userId, null, false);
    }

    /**
     * Whether this reader and that requester belong to the same organisation.
     *
     * <p>The reader's own null is checked and the requester's is not, because only one of them can
     * be null: {@link RequesterRef} and the {@code organization_id NOT NULL} column both refuse a
     * request without one. A symmetric null check would read as caution and be code that cannot run.
     *
     * <p>The check that is here matters: {@code Objects.equals(null, null)} is true, so a reader with
     * no organisation must not be allowed to ask this question at all — otherwise "unaffiliated"
     * would become a membership of its own.
     */
    public boolean sharesOrganizationWith(RequesterRef requester) {
        return organizationId != null && organizationId.equals(requester.organizationId());
    }
}
