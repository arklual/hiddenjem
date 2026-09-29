package dev.horizon.ingestion.connector.regulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhraseMatcherTest {

    @Test
    @DisplayName("слова формулировки — подряд и целыми словами")
    void wordsInSequence() {
        var matcher = new PhraseMatcher(List.of("central bank digital currency"));

        assertThat(matcher.matches("A retail central bank digital currency pilot"))
                .isTrue();
        assertThat(matcher.matches("Central bank digital currencies (CBDCs) in cross-border use"))
                .isTrue();
        // Все слова есть, но не подряд: так выглядит почти любая страница BIS.
        assertThat(matcher.matches("The central bank studied digital payments in a foreign currency"))
                .isFalse();
    }

    @Test
    @DisplayName("короткое слово — только целиком: «ai» не находит «aid», «bank» не находит «bankruptcy»")
    void shortWordsAreWhole() {
        assertThat(new PhraseMatcher(List.of("edge ai")).matches("edge aid for refugees"))
                .isFalse();
        assertThat(new PhraseMatcher(List.of("edge ai")).matches("Edge AI chips"))
                .isTrue();
        assertThat(new PhraseMatcher(List.of("bank")).matches("bankruptcy rules"))
                .isFalse();
        assertThat(new PhraseMatcher(List.of("bank")).matches("two banks")).isTrue();
    }

    @Test
    @DisplayName("британское написание и русские окончания")
    void spellingAndEndings() {
        assertThat(new PhraseMatcher(List.of("tokenization")).matches("Exploring tokenisation of payments"))
                .isTrue();
        assertThat(new PhraseMatcher(List.of("цифровой рубль")).matches("Уроки о цифровом рубле для школьников"))
                .isTrue();
        assertThat(new PhraseMatcher(List.of("цифровой рубль")).matches("С цифрового рубля откроется сезон"))
                .isTrue();
        assertThat(new PhraseMatcher(List.of("stablecoin")).matches("firms issuing stablecoins"))
                .isTrue();
        assertThat(new PhraseMatcher(List.of("open banking")).matches("Open-banking APIs"))
                .isTrue();
    }
}
