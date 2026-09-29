package dev.horizon.ingestion.connector.gdelt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Выражение запроса GDELT и разбор его даты. */
class GdeltTest {

    @Test
    @DisplayName("цели через OR в скобках; короткие слова и коды классификатора отброшены")
    void queryShape() {
        assertThat(GdeltConnector.query(List.of("ai", "cs.AI", "machine learning", "deep learning")))
                .isEqualTo("(\"machine learning\" OR \"deep learning\")");
    }

    @Test
    @DisplayName("«ai» роняет запрос GDELT целиком, поэтому одно короткое слово — пустой запрос")
    void shortWordsAloneYieldNothing() {
        assertThat(GdeltConnector.query(List.of("ai", "nlp"))).isEmpty();
    }

    @Test
    @DisplayName("дата обнаружения 20240102T101500Z — второе января")
    void seenDate() {
        assertThat(GdeltNormalizer.seenOn("20240102T101500Z")).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThatThrownBy(() -> GdeltNormalizer.seenOn("unknown")).isInstanceOf(IllegalArgumentException.class);
    }
}
