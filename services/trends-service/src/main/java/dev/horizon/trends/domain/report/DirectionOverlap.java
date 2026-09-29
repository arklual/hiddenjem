package dev.horizon.trends.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Topics that surfaced in more than one tracked direction (BR-A27…BR-A31, JTBD-11).
 *
 * <p>A topic found in one direction is an observation. The same topic found independently in two
 * unrelated directions is convergence — a sign the technology is leaving its niche — and the
 * emergence methodology cannot see it by construction: every indicator is computed inside the corpus
 * of a single question. This is the one place in the product where directions are read together.
 *
 * <p>Deliberately no scores. Per §12 of the methodology a score is normalised within its own corpus,
 * so scores of different directions share no scale; a number shown side by side will be compared by
 * the reader whatever the caption says. What can be compared is composition and position, and that
 * is what this carries.
 */
public record DirectionOverlap(List<SharedTopic> topics, int directionsCompared, int directionsSkipped) {

    /**
     * One topic and every direction it appears in.
     *
     * @param trendKey the methodology's own identity of the topic — the same key feedback, deltas
     *     and term traces use
     * @param title the topic as the highest-ranked of its occurrences names it
     * @param appearances one per direction, in the order the topic is most visible
     */
    public record SharedTopic(String trendKey, String title, List<Appearance> appearances) {

        public SharedTopic {
            appearances = List.copyOf(appearances);
        }

        /** The rank the topic holds in this direction — second place and fifteenth are not the same claim. */
        public record Appearance(UUID savedDomainId, String query, UUID reportId, int rank) {}

        /** How visible the topic is at its best — the tiebreak after "how many directions". */
        public int bestRank() {
            return appearances.stream().mapToInt(Appearance::rank).min().orElse(Integer.MAX_VALUE);
        }
    }

    /** One direction's report, as this calculation needs to see it. */
    public record DirectionReport(UUID savedDomainId, String query, UUID reportId, TrendReport report) {}

    public DirectionOverlap {
        topics = List.copyOf(topics);
    }

    /**
     * @param analysed the directions that have a report; those without one are counted, not included
     * @param skipped how many tracked directions had nothing to compare — see P6: dropping them
     *     silently would turn "no overlap" into a claim about the field rather than about what has
     *     been run
     */
    public static DirectionOverlap of(List<DirectionReport> analysed, int skipped) {
        // Insertion-ordered so that directions keep the order they were given; the final ordering is
        // applied to topics, and within a topic the analyst reads the directions they know.
        Map<String, List<SharedTopic.Appearance>> byKey = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, Integer> bestRankSeen = new LinkedHashMap<>();

        for (DirectionReport direction : analysed) {
            for (RankedTrend trend : direction.report().trends()) {
                var appearances = byKey.computeIfAbsent(trend.trendKey(), key -> new ArrayList<>());
                // P3: one direction contributes at most one appearance. Ranks are unique within a
                // report, but making the rule explicit here is what keeps "in three directions" from
                // ever meaning "in two, one of them twice".
                boolean alreadyFromThisDirection =
                        appearances.stream().anyMatch(a -> a.savedDomainId().equals(direction.savedDomainId()));
                if (alreadyFromThisDirection) {
                    continue;
                }
                appearances.add(new SharedTopic.Appearance(
                        direction.savedDomainId(), direction.query(), direction.reportId(), trend.rank()));

                // The title of the best-ranked occurrence: the same key can be phrased slightly
                // differently across reports, and the one where the topic is most prominent is the
                // phrasing the analyst is most likely to recognise.
                Integer best = bestRankSeen.get(trend.trendKey());
                if (best == null || trend.rank() < best) {
                    bestRankSeen.put(trend.trendKey(), trend.rank());
                    titles.put(trend.trendKey(), trend.title());
                }
            }
        }

        var shared = new ArrayList<SharedTopic>();
        for (var entry : byKey.entrySet()) {
            // P2: a topic in a single direction is not an overlap. A list that admits everything does
            // not answer the question it was made for.
            if (entry.getValue().size() < 2) {
                continue;
            }
            // Sorted, because the record says they are. Left in load order they would follow how
            // recently each direction was saved — so a topic at №14 could sit above the same topic at
            // №2, and the analyst would read the weaker occurrence first.
            var appearances = new ArrayList<>(entry.getValue());
            appearances.sort(
                    Comparator.comparingInt(SharedTopic.Appearance::rank).thenComparing(SharedTopic.Appearance::query));
            shared.add(new SharedTopic(entry.getKey(), titles.get(entry.getKey()), appearances));
        }

        // P4. Strength of the signal first, then how visible the topic is where it does appear, then
        // the key — the last comparator exists so that two topics tied on both still come back in the
        // same order on every call (ADR-0015).
        shared.sort(Comparator.comparingInt(
                        (SharedTopic topic) -> -topic.appearances().size())
                .thenComparingInt(SharedTopic::bestRank)
                .thenComparing(SharedTopic::trendKey));

        return new DirectionOverlap(shared, analysed.size(), skipped);
    }
}
