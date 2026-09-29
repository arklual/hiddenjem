package dev.horizon.platform.common.util;

import java.util.Collection;
import java.util.Objects;

/**
 * Fail-fast argument checks for domain constructors and value objects.
 *
 * <p>Domain invariants are enforced at construction time (an object that exists is always valid).
 * These helpers throw {@link IllegalArgumentException} — mapped to HTTP 400 by the platform error
 * handler when they escape, but their primary purpose is to make invalid domain states
 * unrepresentable rather than to produce API responses.
 */
public final class Guards {

    private Guards() {}

    public static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " must not be null");
    }

    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static String requireLength(String value, String name, int min, int max) {
        requireText(value, name);
        int length = value.trim().length();
        if (length < min || length > max) {
            throw new IllegalArgumentException(
                    "%s length must be between %d and %d, was %d".formatted(name, min, max, length));
        }
        return value;
    }

    public static int requireRange(int value, String name, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("%s must be between %d and %d, was %d".formatted(name, min, max, value));
        }
        return value;
    }

    public static double requireRange(double value, String name, double min, double max) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException("%s must be between %s and %s, was %s".formatted(name, min, max, value));
        }
        return value;
    }

    public static <T extends Collection<?>> T requireNotEmpty(T value, String name) {
        requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static void requireArgument(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
