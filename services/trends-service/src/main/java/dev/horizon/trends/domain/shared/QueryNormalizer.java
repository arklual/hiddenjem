package dev.horizon.trends.domain.shared;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonicalises a free-text technology domain query.
 *
 * <p>The normalised form is the cache key, the deduplication key and the identity used to find a
 * previously computed report (FR-02.2). It must therefore be stable, idempotent
 * ({@code normalize(normalize(x)) == normalize(x)}) and insensitive to the incidental differences
 * users produce — casing, spacing, surrounding punctuation, Unicode composition form.
 *
 * <p>It deliberately does NOT stem or translate: two genuinely different phrasings should remain
 * different queries, because the analysis pipeline handles semantic expansion itself.
 */
public final class QueryNormalizer {

    private static final int MAX_LENGTH = 200;

    private QueryNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace(' ', ' ')
                .replaceAll("[\\p{Cntrl}]", " ")
                // Keep letters, digits, spaces and the few separators that carry meaning in
                // technology names (C++, Wi-Fi, AI/ML, R&D).
                .replaceAll("[^\\p{L}\\p{N} +\\-/&.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH).trim() : value;
    }

    /** Best-effort language detection used only for query expansion hints; never for filtering. */
    public static String detectLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long cyrillic = raw.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.CYRILLIC)
                .count();
        long latin = raw.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN)
                .count();
        if (cyrillic == 0 && latin == 0) {
            return null;
        }
        return cyrillic >= latin ? "ru" : "en";
    }
}
