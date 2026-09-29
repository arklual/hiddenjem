package dev.horizon.trends.domain.research;

import dev.horizon.platform.common.util.Guards;

/** Why a request failed, and whether retrying it could plausibly help. */
public record FailureInfo(String code, String message, boolean retryable) {

    public static final String SAGA_TIMEOUT = "SAGA_TIMEOUT";
    public static final String NO_DOCUMENTS_FOUND = "NO_DOCUMENTS_FOUND";
    public static final String COLLECTION_FAILED = "COLLECTION_FAILED";
    public static final String ANALYSIS_FAILED = "ANALYSIS_FAILED";
    public static final String ASSEMBLY_FAILED = "ASSEMBLY_FAILED";

    public FailureInfo {
        Guards.requireText(code, "failureCode");
        Guards.requireText(message, "failureMessage");
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
    }

    public static FailureInfo timeout() {
        return new FailureInfo(SAGA_TIMEOUT, "Анализ не завершился в отведённое время", true);
    }

    public static FailureInfo noDocuments() {
        return new FailureInfo(
                NO_DOCUMENTS_FOUND,
                "По заданному направлению не найдено документов. Уточните формулировку или расширьте окно анализа.",
                false);
    }
}
