package dev.horizon.platform.common.error;

/** A single field-level validation failure carried in the {@code errors[]} extension of a problem. */
public record FieldViolation(String field, String message, Object rejectedValue) {
    public FieldViolation(String field, String message) {
        this(field, message, null);
    }
}
