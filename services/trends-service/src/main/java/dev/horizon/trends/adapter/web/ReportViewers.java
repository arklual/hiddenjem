package dev.horizon.trends.adapter.web;

import dev.horizon.platform.spring.caller.Caller;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * The authenticated principal, as the reader the domain rule expects.
 *
 * <p>One place, not one per controller. Four copies of the same three-field constructor is four
 * chances for the organisation to be dropped on one path and kept on the next — which is exactly how
 * an access rule acquires a second, weaker version of itself.
 */
final class ReportViewers {

    private ReportViewers() {}

    static ReportViewer of(Caller caller) {
        // Администратора, видящего чужие организации, больше нет: организация одна.
        return new ReportViewer(caller.userId(), caller.organizationId(), false);
    }
}
