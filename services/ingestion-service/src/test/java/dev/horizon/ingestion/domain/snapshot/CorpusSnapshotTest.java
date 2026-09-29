package dev.horizon.ingestion.domain.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snapshot is the mechanism that turns "our methodology is reproducible" from a claim into a
 * property (ADR-0015, BR-B6). Its content hash must identify the document set and nothing else:
 * two collections that gathered the same documents must be indistinguishable, and any difference in
 * membership must change the hash.
 */
class CorpusSnapshotTest {

    private static final LocalDate FROM = LocalDate.of(2019, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 1);

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(UUID.fromString("00000000-0000-7000-8000-%012d".formatted(i)));
        }
        return ids;
    }

    private static CorpusSnapshot snapshot(List<UUID> documentIds, List<String> used, List<String> unavailable) {
        return CorpusSnapshot.assemble(
                UUID.fromString("00000000-0000-7000-8000-0000000000aa"),
                "технологии в искусственном интеллекте",
                FROM,
                TO,
                documentIds,
                used,
                unavailable);
    }

    @Test
    @DisplayName("the content hash does not depend on the order documents arrived in")
    void hashIsOrderIndependent() {
        // Connectors run concurrently, so arrival order varies between identical runs. If that
        // leaked into the hash, the same corpus would look like two different corpora and every
        // reproducibility guarantee downstream would be void.
        List<UUID> ordered = ids(50);
        List<UUID> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new java.util.Random(42));

        assertThat(snapshot(shuffled, List.of("arxiv"), List.of()).contentHash())
                .isEqualTo(snapshot(ordered, List.of("arxiv"), List.of()).contentHash());
    }

    @Test
    @DisplayName("duplicate ids collapse — the same document twice is still one document")
    void duplicatesAreCollapsed() {
        List<UUID> withDuplicates = new ArrayList<>(ids(10));
        withDuplicates.addAll(ids(10));

        var deduplicated = snapshot(withDuplicates, List.of("arxiv"), List.of());

        assertThat(deduplicated.documentIds()).hasSize(10);
        assertThat(deduplicated.contentHash())
                .isEqualTo(snapshot(ids(10), List.of("arxiv"), List.of()).contentHash());
    }

    @Test
    @DisplayName("adding or removing a single document changes the hash")
    void membershipChangesTheHash() {
        String ten = snapshot(ids(10), List.of("arxiv"), List.of()).contentHash();
        String eleven = snapshot(ids(11), List.of("arxiv"), List.of()).contentHash();

        assertThat(eleven).isNotEqualTo(ten);
    }

    @Test
    @DisplayName("the hash depends only on membership, not on which sources contributed")
    void hashIgnoresSourceLists() {
        // Deliberate: a rerun that reached the same documents through a different mix of sources
        // is the same corpus, and must be able to reuse a cached analysis.
        String viaArxiv = snapshot(ids(10), List.of("arxiv"), List.of()).contentHash();
        String viaMany = snapshot(ids(10), List.of("arxiv", "crossref", "openalex"), List.of("github"))
                .contentHash();

        assertThat(viaMany).isEqualTo(viaArxiv);
    }

    @Test
    @DisplayName("an unavailable source makes the snapshot partial without being asked to")
    void unavailableSourceMarksPartial() {
        // BRULE-8: incompleteness is derived from the facts, never from a caller's optimism.
        assertThat(snapshot(ids(5), List.of("arxiv"), List.of("uspto")).partial())
                .isTrue();
        assertThat(snapshot(ids(5), List.of("arxiv"), List.of()).partial()).isFalse();
    }

    @Test
    @DisplayName("source lists are deduplicated and stably ordered")
    void sourceListsAreCanonical() {
        var result = snapshot(ids(3), List.of("crossref", "arxiv", "crossref"), List.of("github", "github"));

        assertThat(result.sourcesUsed()).containsExactly("arxiv", "crossref");
        assertThat(result.unavailableSources()).containsExactly("github");
    }

    @Test
    @DisplayName("the hash is a SHA-256 digest and an empty corpus still produces one")
    void hashIsAlwaysWellFormed() {
        assertThat(snapshot(ids(3), List.of("arxiv"), List.of()).contentHash())
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(snapshot(List.of(), List.of(), List.of()).contentHash()).hasSize(64);
    }

    @Test
    @DisplayName("assembling the same inputs twice gives byte-identical results")
    void assemblyIsPure() {
        var first = snapshot(ids(20), List.of("arxiv", "crossref"), List.of());
        var second = snapshot(ids(20), List.of("arxiv", "crossref"), List.of());

        assertThat(second.contentHash()).isEqualTo(first.contentHash());
        assertThat(second.documentIds()).isEqualTo(first.documentIds());
    }
}
