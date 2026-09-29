package dev.horizon.ingestion.domain.document;

import dev.horizon.platform.common.util.Guards;

/**
 * A subject heading as reported by the source (OpenAlex concept, arXiv category, CPC class, GitHub
 * topic).
 *
 * <p>Deliberately "raw": these are stored as they arrive ({@code document_topics_raw}) and are never
 * mistaken for the trends the analysis engine derives. They are a useful prior, not a result.
 */
public record DocumentTopic(String code, String label, Double score) {

    public DocumentTopic {
        code = Guards.requireText(code, "topic.code").trim();
        if (code.length() > 64) {
            code = code.substring(0, 64);
        }
        label = blankToNull(label);
        if (label != null && label.length() > 200) {
            label = label.substring(0, 200);
        }
        if (score != null && (score.isNaN() || score < 0.0 || score > 1.0)) {
            score = null;
        }
    }

    public static DocumentTopic of(String code) {
        return new DocumentTopic(code, null, null);
    }

    public static DocumentTopic of(String code, String label, Double score) {
        return new DocumentTopic(code, label, score);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
