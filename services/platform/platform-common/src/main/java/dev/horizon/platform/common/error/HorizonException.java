package dev.horizon.platform.common.error;

import java.util.List;

/**
 * Base class for expected, business-meaningful failures.
 *
 * <p>Carries a {@link ProblemType} so that every layer above can render a correct RFC 9457 response
 * without a chain of instanceof checks. Unexpected failures are deliberately NOT modelled here:
 * they surface as regular exceptions and are mapped to {@code internal-error}, so a missing mapping
 * can never accidentally leak internals to a client.
 */
public class HorizonException extends RuntimeException {

    private final transient ProblemType type;
    private final transient List<FieldViolation> violations;

    public HorizonException(ProblemType type, String detail) {
        this(type, detail, List.of(), null);
    }

    public HorizonException(ProblemType type, String detail, Throwable cause) {
        this(type, detail, List.of(), cause);
    }

    public HorizonException(ProblemType type, String detail, List<FieldViolation> violations, Throwable cause) {
        super(detail, cause);
        this.type = type;
        this.violations = List.copyOf(violations);
    }

    public ProblemType type() {
        return type;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    // ---- Convenient factories for the most common cases -------------------------------------

    public static HorizonException notFound(String resource, Object id) {
        return new HorizonException(ProblemType.NOT_FOUND, "%s %s не найден".formatted(resource, id));
    }

    public static HorizonException conflict(String detail) {
        return new HorizonException(ProblemType.CONFLICT, detail);
    }

    public static HorizonException validation(String detail, List<FieldViolation> violations) {
        return new HorizonException(ProblemType.VALIDATION_ERROR, detail, violations, null);
    }

    public static HorizonException accessDenied(String detail) {
        return new HorizonException(ProblemType.ACCESS_DENIED, detail);
    }

    public static HorizonException illegalTransition(String detail) {
        return new HorizonException(ProblemType.ILLEGAL_STATE_TRANSITION, detail);
    }

    public static HorizonException quotaExceeded(String detail) {
        return new HorizonException(ProblemType.QUOTA_EXCEEDED, detail);
    }
}
