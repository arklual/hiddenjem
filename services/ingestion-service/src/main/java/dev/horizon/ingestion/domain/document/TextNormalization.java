package dev.horizon.ingestion.domain.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic text normalisation behind {@link DedupKey} (FR-04.3).
 *
 * <p>The same work reaches us from several sources with cosmetic differences only: smart quotes vs
 * apostrophes, {@code ﬁ} vs {@code fi}, {@code Müller} vs {@code Muller}, {@code AI} vs {@code ＡＩ},
 * "Smith, John" vs "John Smith", and author lists in a different order. Every one of those must
 * collapse to a single key, or the corpus counts the same paper twice and every indicator derived
 * from document counts is wrong.
 *
 * <p>The pipeline is intentionally boring and fully specified, because it is a <em>persisted</em>
 * decision: keys already written to the database must keep matching keys computed tomorrow.
 *
 * <ol>
 *   <li>NFKC — compatibility composition folds ligatures, full-width forms and superscripts.
 *   <li>NFD + removal of combining marks — diacritics are dropped ({@code é}→{@code e},
 *       {@code ё}→{@code е}); transliteration differences across sources are far more common than
 *       genuine minimal pairs.
 *   <li>Lower case with {@link Locale#ROOT} — never the default locale (the Turkish dotless-i trap).
 *   <li>Every run of non-alphanumeric characters becomes a single space; the result is trimmed.
 * </ol>
 *
 * <p>Author names additionally collapse to {@code surname initial} and are <em>sorted</em>, which
 * makes the key independent of author order and of how much of the given name a source publishes.
 */
public final class TextNormalization {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextNormalization() {}

    /** Canonical form of a title: lower case, unaccented, alphanumeric words separated by one space. */
    public static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lower = withoutMarks.toLowerCase(Locale.ROOT);
        String alphanumeric = NON_ALPHANUMERIC.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(alphanumeric).replaceAll(" ").trim();
    }

    /**
     * Canonical form of one personal name: {@code "surname initial"}.
     *
     * <p>Both western ("John A. Smith") and bibliographic ("Smith, John A.") orders are recognised,
     * so the same person yields the same token regardless of which convention the source follows.
     * Compound surnames survive ("van der Berg, J." → {@code van der berg j}).
     */
    public static String normalizeAuthorName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        int comma = composed.indexOf(',');
        String surnamePart;
        String givenPart;
        if (comma >= 0) {
            surnamePart = composed.substring(0, comma);
            givenPart = composed.substring(comma + 1);
        } else {
            String normalized = normalizeTitle(composed);
            if (normalized.isEmpty()) {
                return "";
            }
            int lastSpace = normalized.lastIndexOf(' ');
            if (lastSpace < 0) {
                return normalized;
            }
            surnamePart = normalized.substring(lastSpace + 1);
            givenPart = normalized.substring(0, lastSpace);
        }
        String surname = normalizeTitle(surnamePart);
        String given = normalizeTitle(givenPart);
        if (surname.isEmpty()) {
            return given;
        }
        return given.isEmpty() ? surname : surname + " " + given.charAt(0);
    }

    /**
     * Canonical, order-independent form of an author list.
     *
     * <p>Sorted and de-duplicated: sources disagree about author order (and about whether
     * consortium members are listed at all), so the set — not the sequence — is what identifies the
     * work.
     */
    public static String normalizeAuthors(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        // TreeSet: natural order + de-duplication in one step, and the iteration order is stable.
        var canonical = new TreeSet<String>();
        for (String name : names) {
            String normalized = normalizeAuthorName(name);
            if (!normalized.isEmpty()) {
                canonical.add(normalized);
            }
        }
        return String.join(";", canonical);
    }

    /** Convenience overload for the domain type. */
    public static String normalizeAuthorList(List<Author> authors) {
        if (authors == null || authors.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(authors.size());
        for (Author author : authors) {
            names.add(author.fullName());
        }
        return normalizeAuthors(names);
    }
}
