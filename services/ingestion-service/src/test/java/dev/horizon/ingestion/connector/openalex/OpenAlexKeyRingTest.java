package dev.horizon.ingestion.connector.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAlexKeyRingTest {
    @Test
    void advancesInOrderOnlyWhenCurrentKeyIsExhausted() {
        var ring = new OpenAlexKeyRing("first", "second,third,second");
        assertThat(ring.current()).isEqualTo("first");
        assertThat(ring.exhausted("first")).isTrue();
        assertThat(ring.current()).isEqualTo("second");
        assertThat(ring.exhausted("first")).isTrue();
        assertThat(ring.current()).isEqualTo("second");
        assertThat(ring.exhausted("second")).isTrue();
        assertThat(ring.current()).isEqualTo("third");
        assertThat(ring.exhausted("third")).isFalse();
    }
}
