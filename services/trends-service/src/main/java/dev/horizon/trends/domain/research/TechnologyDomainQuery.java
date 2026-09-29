package dev.horizon.trends.domain.research;

import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.shared.QueryNormalizer;

/**
 * The technology direction the analyst asked about.
 *
 * <p>Both forms are kept: {@code raw} is what the user typed and is echoed back verbatim in the UI,
 * {@code normalized} is what the system reasons with. Conflating them would either corrupt the
 * user's wording or make caching unreliable.
 */
public record TechnologyDomainQuery(String raw, String normalized, String language) {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 200;

    public TechnologyDomainQuery {
        Guards.requireLength(raw, "query", MIN_LENGTH, MAX_LENGTH);
        Guards.requireText(normalized, "normalizedQuery");
    }

    public static TechnologyDomainQuery of(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String normalized = QueryNormalizer.normalize(trimmed);
        if (normalized.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Запрос должен содержать не менее %d значащих символов".formatted(MIN_LENGTH));
        }
        return new TechnologyDomainQuery(trimmed, normalized, QueryNormalizer.detectLanguage(trimmed));
    }
}
