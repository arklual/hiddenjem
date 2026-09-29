package dev.horizon.trends.adapter.web.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.domain.report.ReportDelta;

/**
 * OpenAPI {@code ReportDelta} — what changed since the previous version.
 *
 * <p>{@code unavailableReason} is part of the contract rather than an empty body. Empty groups read
 * as "nothing changed", which is a statement about the world and the exact opposite of "there is
 * nothing to compare with" and "comparing would be wrong". A client that received the same answer
 * to three different questions would show the analyst the wrong one two times out of three.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportDeltaView(
        UUID previousReportId,
        Integer previousVersion,
        List<EntryView> entered,
        List<EntryView> left,
        List<EntryView> stayed,
        String unavailableReason) {

    /**
     * @param rankChange places climbed since the previous version — positive means "moved up",
     *     although ranks count downwards. Absent for a topic present in only one version
     * @param scoreChange signed change of the emergence score, absent on the same condition
     */
    public record EntryView(
            String trendKey, String title, int rank, Integer rankChange, double score, Double scoreChange) {}

    public static ReportDeltaView from(ReportDelta delta) {
        if (!delta.isAvailable()) {
            return new ReportDeltaView(null, null, List.of(), List.of(), List.of(), slugOf(delta.unavailableReason()));
        }
        return new ReportDeltaView(
                delta.previousReportId().value(),
                delta.previousVersion(),
                map(delta.entered()),
                map(delta.left()),
                map(delta.stayed()),
                null);
    }

    private static String slugOf(ReportDelta.Unavailable reason) {
        return reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static List<EntryView> map(List<ReportDelta.Entry> entries) {
        return entries.stream()
                .map(entry -> new EntryView(
                        entry.trendKey(),
                        entry.title(),
                        entry.rank(),
                        entry.rankChange(),
                        entry.score(),
                        entry.scoreChange()))
                .toList();
    }
}
