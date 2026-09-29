package dev.horizon.platform.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GuardsTest {

    @Nested
    @DisplayName("requireText")
    class RequireText {
        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "\n", "   "})
        @DisplayName("rejects blank values, not just null")
        void rejectsBlank(String value) {
            assertThatThrownBy(() -> Guards.requireText(value, "field"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("field");
        }

        @Test
        void acceptsNonBlank() {
            assertThat(Guards.requireText("value", "field")).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("requireLength")
    class RequireLength {
        @Test
        @DisplayName("measures the trimmed length, so trailing spaces cannot smuggle a value through")
        void usesTrimmedLength() {
            assertThatThrownBy(() -> Guards.requireLength("ab   ", "query", 3, 200))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> Guards.requireLength("  abc  ", "query", 3, 200))
                    .doesNotThrowAnyException();
        }

        @Test
        void enforcesBothBounds() {
            assertThatThrownBy(() -> Guards.requireLength("abcd", "q", 1, 3)).hasMessageContaining("between 1 and 3");
        }
    }

    @Nested
    @DisplayName("requireRange")
    class RequireRange {
        @Test
        @DisplayName("accepts the inclusive bounds")
        void boundsAreInclusive() {
            assertThat(Guards.requireRange(5, "n", 5, 10)).isEqualTo(5);
            assertThat(Guards.requireRange(10, "n", 5, 10)).isEqualTo(10);
        }

        @Test
        @DisplayName("rejects NaN — otherwise every comparison silently passes")
        void rejectsNaN() {
            assertThatThrownBy(() -> Guards.requireRange(Double.NaN, "score", 0.0, 1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsOutOfRange() {
            assertThatThrownBy(() -> Guards.requireRange(1.5, "confidence", 0.0, 1.0))
                    .hasMessageContaining("confidence");
        }
    }

    @Test
    @DisplayName("requireNotEmpty rejects an empty collection")
    void requireNotEmpty() {
        assertThatThrownBy(() -> Guards.requireNotEmpty(List.of(), "evidence"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
        assertThat(Guards.requireNotEmpty(List.of("a"), "evidence")).hasSize(1);
    }

    @Test
    @DisplayName("requireState signals a broken invariant, requireArgument a bad input")
    void distinguishesStateFromArgument() {
        assertThatThrownBy(() -> Guards.requireState(false, "boom")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Guards.requireArgument(false, "boom")).isInstanceOf(IllegalArgumentException.class);
    }
}
