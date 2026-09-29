package dev.horizon.ingestion.connector.openalex;

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

import dev.horizon.ingestion.connector.openalex.model.OpenAlexPage;
import dev.horizon.ingestion.connector.openalex.model.OpenAlexWork;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Разбор подлинного ответа OpenAlex (`docs/01-analysis/27-connector-format-spec.md`, C1–C6).
 *
 * <p>OpenAlex — второй по объёму источник и единственный, который отдаёт аннотацию **не текстом**:
 * вместо неё приходит инвертированный индекс «слово → позиции», и текст нужно собирать обратно.
 * Сборка, вернувшая пустую строку, ничего не роняет — документ дойдёт до анализа без аннотации и
 * почти не даст терминов. В отчёте это выглядит как «по направлению мало что нашлось».
 */
class OpenAlexWorksTest {

    private static OpenAlexPage page;

    @BeforeAll
    static void parseRealResponse() throws IOException {
        // Разбор той же настройкой, что и в бою: неизвестные поля игнорируются, потому что OpenAlex
        // добавляет их регулярно и падать на этом нельзя.
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream stream =
                OpenAlexWorksTest.class.getResourceAsStream("/connector/openalex/works-response.json")) {
            assertThat(stream).as("записанный ответ OpenAlex").isNotNull();
            page = mapper.readValue(stream, OpenAlexPage.class);
        }
    }

    private static Document documentAt(int index) {
        OpenAlexWork work = page.results().get(index);
        return new OpenAlexNormalizer().normalize(new OpenAlexWork.Raw(work, "openalex", work.id(), provenance()));
    }

    private static Provenance provenance() {
        return new Provenance(
                "openalex",
                Instant.parse("2026-03-01T10:00:00Z"),
                "https://api.openalex.org/works?search=speculative+decoding",
                200,
                "b".repeat(64),
                null);
    }

    @Test
    @DisplayName("разбирается страница целиком со счётчиками")
    void thePageAndItsCountersAreParsed() {
        assertThat(page.results()).hasSize(2);
        assertThat(page.meta().count()).isEqualTo(412);
    }

    @Test
    @DisplayName("аннотация собирается из инвертированного индекса")
    void theAbstractIsRebuiltFromTheInvertedIndex() {
        // Сердце этого коннектора. Порядок слов задаётся позициями, а не порядком ключей в JSON:
        // собрать их «как пришло» дало бы связный на вид текст с переставленными словами — худший
        // вид поломки, потому что он читается нормально.
        Document document = documentAt(0);

        assertThat(document.abstractText())
                .startsWith("Inference from large autoregressive models is slow.")
                .contains("introduce speculative decoding.");
    }

    @Test
    @DisplayName("концепты становятся предметными кодами с весами")
    void conceptsBecomeWeightedSubjectCodes() {
        // На них держится отнесение темы к направлению (§12 методологии). Вес важен не меньше кода:
        // «Computer science» с весом 0.78 и «Natural language processing» с 0.45 — разные
        // утверждения о том, о чём работа.
        Document document = documentAt(0);

        assertThat(document.topics())
                .extracting(DocumentTopic::label)
                .contains("Computer science", "Machine learning", "Natural language processing");
        assertThat(document.topics())
                .allSatisfy(topic -> assertThat(topic.code()).isNotBlank());
    }

    @Test
    @DisplayName("дата публикации разбирается")
    void thePublicationDateIsParsed() {
        assertThat(documentAt(0).publishedOn()).isEqualTo(LocalDate.of(2022, 11, 30));
    }

    @Test
    @DisplayName("авторы и их организации разбираются")
    void authorsAndTheirInstitutionsAreParsed() {
        Document document = documentAt(0);

        assertThat(document.authors()).extracting("fullName").contains("Yaniv Leviathan", "Matan Kalman");
        assertThat(document.authors()).extracting("organizationName").contains("Google (United States)");
    }

    @Test
    @DisplayName("работа без аннотации, локации и авторов разбирается, а не отбрасывается")
    void aWorkWithoutOptionalFieldsStillParses() {
        // Ровно тот случай, ради которого продукт существует: свежая публикация, которую OpenAlex
        // ещё не успел обогатить. Отбросить её значит потерять самый ранний сигнал.
        Document document = documentAt(1);

        assertThat(document.title()).contains("Speculative Sampling");
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2023, 2, 2));
        assertThat(document.authors()).isEmpty();
    }
}
