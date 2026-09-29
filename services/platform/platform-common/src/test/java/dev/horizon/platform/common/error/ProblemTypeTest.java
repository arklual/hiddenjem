package dev.horizon.platform.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProblemTypeTest {

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    @DisplayName("every problem type has a usable slug, title and HTTP status")
    void everyTypeIsWellFormed(ProblemType type) {
        assertThat(type.slug()).matches("[a-z][a-z0-9-]*");
        assertThat(type.title()).isNotBlank();
        assertThat(type.status()).isBetween(400, 599);
        assertThat(type.uri()).startsWith("https://horizon.dev/problems/").endsWith(type.slug());
    }

    @Test
    @DisplayName("slugs are unique — they are the identifier the frontend switches on")
    void slugsAreUnique() {
        var slugs = Arrays.stream(ProblemType.values()).map(ProblemType::slug).collect(Collectors.toSet());

        assertThat(slugs).hasSameSizeAs(Arrays.asList(ProblemType.values()));
    }

    @Test
    @DisplayName("client errors caused by bad input are never advertised as retryable")
    void inputErrorsAreNotRetryable() {
        // Retrying a 400 verbatim can only fail again; telling a client otherwise causes retry storms.
        assertThat(ProblemType.VALIDATION_ERROR.retryable()).isFalse();
        assertThat(ProblemType.MALFORMED_REQUEST.retryable()).isFalse();
        assertThat(ProblemType.ACCESS_DENIED.retryable()).isFalse();
        assertThat(ProblemType.NOT_FOUND.retryable()).isFalse();
    }

    @Test
    @DisplayName("throttling and upstream failures are retryable")
    void transientFailuresAreRetryable() {
        assertThat(ProblemType.RATE_LIMITED.retryable()).isTrue();
        assertThat(ProblemType.QUOTA_EXCEEDED.retryable()).isTrue();
        assertThat(ProblemType.UPSTREAM_UNAVAILABLE.retryable()).isTrue();
    }
}
