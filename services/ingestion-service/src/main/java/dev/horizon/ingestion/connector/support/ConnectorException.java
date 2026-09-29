package dev.horizon.ingestion.connector.support;

/**
 * A source did not answer the way its contract promises.
 *
 * <p>Split into two concrete kinds because the split is exactly the retry rule (FR-04.4): a
 * {@link Retryable} failure (429, 5xx, connection reset) may succeed on a second attempt, a
 * {@link Permanent} one (400, 401, 404, unparsable body) never will and retrying it only burns the
 * rate-limit budget. Expressing that as types rather than as an {@code if} lets Resilience4j decide
 * from the exception class alone.
 */
public abstract sealed class ConnectorException extends RuntimeException
        permits ConnectorException.Retryable, ConnectorException.Permanent {

    private final transient String sourceId;
    private final transient int httpStatus;

    protected ConnectorException(String sourceId, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.sourceId = sourceId;
        this.httpStatus = httpStatus;
    }

    public String sourceId() {
        return sourceId;
    }

    /** HTTP status, or {@code 0} when the failure happened before a response existed. */
    public int httpStatus() {
        return httpStatus;
    }

    public abstract boolean retryable();

    /** Transient upstream failure: 429, 5xx, or a transport error. */
    public static final class Retryable extends ConnectorException {

        public Retryable(String sourceId, int httpStatus, String message) {
            this(sourceId, httpStatus, message, null);
        }

        public Retryable(String sourceId, int httpStatus, String message, Throwable cause) {
            super(sourceId, httpStatus, message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** Our request or their payload is wrong; a retry would produce the same failure. */
    public static final class Permanent extends ConnectorException {

        public Permanent(String sourceId, int httpStatus, String message) {
            this(sourceId, httpStatus, message, null);
        }

        public Permanent(String sourceId, int httpStatus, String message, Throwable cause) {
            super(sourceId, httpStatus, message, cause);
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }
}
