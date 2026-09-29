package dev.horizon.ingestion.connector.crossref;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.connector.crossref.model.CrossrefResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Разбор подлинного ответа Crossref (`docs/01-analysis/27-connector-format-spec.md`, C1–C6).
 *
 * <p>У Crossref два подвоха, и оба дают правдоподобный результат при неверной реализации.
 *
 * <p>Первый — **две даты публикации**: онлайн и печатная, и печатная бывает на полгода позже. Взять
 * печатную значит состарить тему на этот срок; ошибка не видна нигде, кроме индикатора новизны,
 * который и решает, считать ли тему зарождающейся.
 *
 * <p>Второй — **аннотация приходит разметкой JATS**. Отдать её как есть в извлечение терминов значит
 * сделать «jats» одним из самых частых токенов корпуса: он попадёт в кандидаты, пройдёт пороги по
 * частоте и будет конкурировать с настоящими технологиями.
 */
class CrossrefWorksTest {

    private static CrossrefResponse response;

    @BeforeAll
    static void parseRealResponse() throws IOException {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream stream =
                CrossrefWorksTest.class.getResourceAsStream("/connector/crossref/works-response.json")) {
            assertThat(stream).as("записанный ответ Crossref").isNotNull();
            response = mapper.readValue(stream, CrossrefResponse.class);
        }
    }

    private static Document documentAt(int index) {
        CrossrefResponse.Item item = response.message().items().get(index);
        return new CrossrefNormalizer().normalize(new CrossrefResponse.Raw(item, "crossref", item.doi(), provenance()));
    }

    private static Provenance provenance() {
        return new Provenance(
                "crossref",
                Instant.parse("2026-03-01T10:00:00Z"),
                "https://api.crossref.org/works?query=speculative+decoding",
                200,
                "c".repeat(64),
                null);
    }

    @Test
    @DisplayName("разбирается список работ со счётчиками")
    void theWorkListAndItsCountersAreParsed() {
        assertThat(response.message().items()).hasSize(2);
        assertThat(response.message().totalResults()).isEqualTo(84);
    }

    @Test
    @DisplayName("берётся онлайн-дата, а не печатная")
    void theOnlineDateWinsOverThePrintOne() {
        // В образце они различаются на полгода намеренно: иначе тест не отличил бы верный выбор от
        // случайного совпадения.
        assertThat(documentAt(0).publishedOn()).isEqualTo(LocalDate.of(2024, 5, 13));
    }

    @Test
    @DisplayName("без онлайн-даты берётся печатная")
    void thePrintDateIsUsedWhenThereIsNoOnlineOne() {
        // Обычный случай для журнальной статьи. Отбросить такую работу значило бы потерять целый
        // класс источников.
        assertThat(documentAt(1).publishedOn()).isEqualTo(LocalDate.of(2023, 7, 4));
    }

    @Test
    @DisplayName("разметка JATS вычищена из аннотации")
    void jatsMarkupIsStrippedFromTheAbstract() {
        Document document = documentAt(0);

        assertThat(document.abstractText())
                .doesNotContain("jats")
                .doesNotContain("<")
                .contains("speculative decoding");
    }

    @Test
    @DisplayName("заголовок берётся из массива")
    void theTitleComesOutOfItsArray() {
        assertThat(documentAt(0).title()).isEqualTo("Speculative Decoding for Latency-Critical Serving");
    }

    @Test
    @DisplayName("авторы собираются из имени и фамилии, организации сохраняются")
    void authorsAreAssembledFromGivenAndFamilyNames() {
        Document document = documentAt(0);

        assertThat(document.authors()).extracting("fullName").contains("Anna Petrova", "Li Wei");
        assertThat(document.authors())
                .extracting("organizationName")
                .contains("Skolkovo Institute of Science and Technology");
    }
}
