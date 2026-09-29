package dev.horizon.ingestion.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Deduplication is the quality floor of the whole corpus (FR-04.3).
 *
 * <p>The same paper reaches Horizon through arXiv, Crossref and OpenAlex with different casing,
 * punctuation, author ordering and metadata completeness. If the key is not stable across those
 * variations, one work is counted three times — and every downstream indicator (document counts,
 * growth slope, diffusion) is inflated by an artefact rather than by a signal.
 */
class DedupKeyTest {

    private static DocumentIdentifiers identifiers(String doi, String arxivId) {
        return new DocumentIdentifiers(doi, arxivId, null, "https://example.org/doc");
    }

    private static DedupKey key(String doi, String arxivId, String title, List<String> authors, int year) {
        return DedupKey.compute(identifiers(doi, arxivId), title, authors, year);
    }

    @Nested
    @DisplayName("что дедупликация обещает и чего не обещает")
    class TheReachOfDeduplication {

        /**
         * Ключ не зависит от даты — а строка хранения зависит.
         *
         * <p>Обе уникальности таблицы включают {@code published_on}:
         * {@code UNIQUE (source_id, external_id, published_on)} и
         * {@code UNIQUE (dedup_key, published_on)}. Это не решение о дедупликации, а следствие
         * партиционирования: PostgreSQL требует, чтобы ключ секционирования входил в любое
         * ограничение уникальности.
         *
         * <p>Следствие настоящее и стоит того, чтобы быть записанным: одна и та же работа,
         * пришедшая из двух источников с разными датами публикации — обычное расхождение, один
         * отдаёт дату онлайн-первой публикации, другой печатной, — ляжет двумя строками. BRULE-1 это
         * переживает (он считает организации, а не документы), а `totalDocuments`, `weakness` и
         * `confidence` считают документы и сдвинутся.
         */
        @Test
        @DisplayName("одна работа с двумя датами публикации даёт один ключ и две строки хранения")
        void theSameWorkWithTwoDatesIsStoredTwice() {
            var key = key("10.1234/abc", null, "Title", List.of("A"), 2024);
            var sameWorkLaterDate = key("10.1234/abc", null, "Title", List.of("A"), 2024);

            // Ключ один и тот же: DOI не зависит ни от даты, ни от года.
            assertThat(sameWorkLaterDate.value()).isEqualTo(key.value());
            // А различает строки пара (ключ, дата) — и при разных датах она разная.
            assertThat(storageIdentity(key, "2024-03-01")).isNotEqualTo(storageIdentity(key, "2024-11-15"));
        }

        @Test
        @DisplayName("без DOI разные годы дают и разные ключи — дубликат возникает раньше")
        void withoutADoiTheYearIsPartOfTheKey() {
            // Здесь дублирование не спасти и парой: препринт 2024 года и его версия 2025-го
            // различаются уже ключом, потому что год входит в него по построению.
            var preprint = key(null, null, "Title", List.of("A"), 2024);
            var published = key(null, null, "Title", List.of("A"), 2025);

            assertThat(preprint.value()).isNotEqualTo(published.value());
        }

        @Test
        @DisplayName("препринт и журнальная версия — разные ключи: это разные записи одной работы")
        void aPreprintAndItsJournalVersionAreDifferentKeys() {
            // Не дефект, а граница: у препринта arXiv-идентификатор, у статьи DOI. Свести их можно
            // только внешним справочником соответствий, которого у продукта нет.
            var preprint = key(null, "2401.00001", "Title", List.of("A"), 2024);
            var journal = key("10.1234/abc", null, "Title", List.of("A"), 2024);

            assertThat(preprint.value()).isNotEqualTo(journal.value());
            assertThat(preprint.basis()).isEqualTo(DedupKey.Basis.ARXIV_ID);
            assertThat(journal.basis()).isEqualTo(DedupKey.Basis.DOI);
        }

        /** Пара, по которой таблица различает строки. */
        private static String storageIdentity(DedupKey key, String publishedOn) {
            return key.value() + "|" + publishedOn;
        }
    }

    @Nested
    @DisplayName("basis selection")
    class BasisSelection {

        @Test
        @DisplayName("prefers DOI — the only globally unique identifier available")
        void prefersDoi() {
            var result = key("10.1234/abc", "2401.00001", "Title", List.of("A"), 2024);

            assertThat(result.basis()).isEqualTo(DedupKey.Basis.DOI);
        }

        @Test
        @DisplayName("falls back to the arXiv id when there is no DOI")
        void fallsBackToArxiv() {
            var result = key(null, "2401.00001", "Title", List.of("A"), 2024);

            assertThat(result.basis()).isEqualTo(DedupKey.Basis.ARXIV_ID);
        }

        @Test
        @DisplayName("falls back to title+authors+year when there is no identifier at all")
        void fallsBackToContent() {
            var result = key(null, null, "Neuro-symbolic reasoning", List.of("Ivanov I."), 2024);

            assertThat(result.basis()).isEqualTo(DedupKey.Basis.TITLE_AUTHORS_YEAR);
        }

        @Test
        @DisplayName("refuses a document with neither identifier nor title — it cannot be deduplicated")
        void rejectsUnidentifiableDocument() {
            assertThatThrownBy(() -> key(null, null, "   ", List.of("A"), 2024))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("stability of the content-based key")
    class ContentKeyStability {

        private static final String TITLE = "Neuro-Symbolic Program Synthesis";
        private static final List<String> AUTHORS = List.of("Ivan Ivanov", "Anna Petrova");

        private String contentKey(String title, List<String> authors, int year) {
            return key(null, null, title, authors, year).value();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "different case                  | NEURO-SYMBOLIC PROGRAM SYNTHESIS",
                    "trailing punctuation            | Neuro-Symbolic Program Synthesis.",
                    "collapsed whitespace            | Neuro-Symbolic   Program\tSynthesis",
                    "surrounding whitespace          |    Neuro-Symbolic Program Synthesis   ",
                    "typographic dash                | Neuro–Symbolic Program Synthesis",
                })
        @DisplayName("the same work keeps one key across cosmetic title differences")
        void titleVariationsProduceTheSameKey(String scenario, String variant) {
            assertThat(contentKey(variant, AUTHORS, 2024))
                    .as("scenario: %s", scenario)
                    .isEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("author order does not matter — sources list authors differently")
        void authorOrderDoesNotMatter() {
            assertThat(contentKey(TITLE, List.of("Anna Petrova", "Ivan Ivanov"), 2024))
                    .isEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("initials and full given names collapse to the same key")
        void initialsCollapse() {
            assertThat(contentKey(TITLE, List.of("I. Ivanov", "A. Petrova"), 2024))
                    .isEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("a genuinely different title yields a different key")
        void differentTitleDiffers() {
            assertThat(contentKey("Neuro-Symbolic Program Repair", AUTHORS, 2024))
                    .isNotEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("a different year yields a different key — reprints are separate documents")
        void differentYearDiffers() {
            assertThat(contentKey(TITLE, AUTHORS, 2023)).isNotEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("a different author set yields a different key")
        void differentAuthorsDiffer() {
            assertThat(contentKey(TITLE, List.of("Someone Else"), 2024)).isNotEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }

        @Test
        @DisplayName("computation is pure: the same input always gives the same key")
        void isDeterministic() {
            assertThat(contentKey(TITLE, AUTHORS, 2024)).isEqualTo(contentKey(TITLE, AUTHORS, 2024));
        }
    }

    @Nested
    @DisplayName("identifier normalisation")
    class IdentifierNormalisation {

        @Test
        @DisplayName("DOI case and resolver prefix do not create two keys")
        void doiIsNormalised() {
            var bare = key("10.1234/ABC", null, "T", List.of("A"), 2024);
            var prefixed = key("https://doi.org/10.1234/abc", null, "T", List.of("A"), 2024);

            assertThat(prefixed.value()).isEqualTo(bare.value());
        }

        @Test
        @DisplayName("arXiv version suffix does not create two keys")
        void arxivVersionIsNormalised() {
            var v1 = key(null, "arXiv:2401.00001v1", "T", List.of("A"), 2024);
            var bare = key(null, "2401.00001", "T", List.of("A"), 2024);

            assertThat(v1.value()).isEqualTo(bare.value());
        }
    }

    @Test
    @DisplayName("the key is always a 64-character SHA-256 digest")
    void keyIsAlwaysASha256Digest() {
        for (DedupKey candidate : List.of(
                key("10.1/x", null, "T", List.of("A"), 2024),
                key(null, "2401.1", "T", List.of("A"), 2024),
                key(null, null, "Title only", List.of(), 2024))) {
            assertThat(candidate.value()).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Test
    @DisplayName("a key restored from storage keeps its value and is marked as restored")
    void restoredKeyIsMarked() {
        String stored = key("10.1/x", null, "T", List.of("A"), 2024).value();

        var restored = DedupKey.ofStoredValue(stored);

        assertThat(restored.value()).isEqualTo(stored);
        assertThat(restored.basis()).isEqualTo(DedupKey.Basis.RESTORED);
    }
}
