package dev.horizon.ingestion.connector.github;

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

import dev.horizon.ingestion.connector.github.model.GitHubSearchResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Разбор подлинного ответа GitHub Search API (`docs/01-analysis/27-connector-format-spec.md`).
 *
 * <p>Репозиторий — не публикация, и отображение его в документ держится на трёх решениях, каждое из
 * которых меняет выводы методологии.
 *
 * <p><b>Дата — создание, а не последнее изменение.</b> У живого репозитория они расходятся на годы:
 * в образце — на три с лишним. Взять дату изменения значит объявить зарождающимся всё, что активно
 * поддерживается, то есть ровно зрелое.
 *
 * <p><b>Организация-владелец — организация, личный аккаунт — нет.</b> На этом держится правило
 * достоверности «две независимые организации»: если засчитывать личные аккаунты, тема подтверждается
 * двумя энтузиастами и попадает в отчёт как индустриальный сигнал.
 *
 * <p><b>Звёзды и форки — метрики, а не баллы.</b> Они записываются сырыми, а взвешивает их
 * методология; сложить их в оценку прямо здесь значило бы спрятать решение в коннекторе.
 */
class GitHubSearchTest {

    private static GitHubSearchResponse response;

    @BeforeAll
    static void parseRealResponse() throws IOException {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream stream =
                GitHubSearchTest.class.getResourceAsStream("/connector/github/search-response.json")) {
            assertThat(stream).as("записанный ответ GitHub").isNotNull();
            response = mapper.readValue(stream, GitHubSearchResponse.class);
        }
    }

    private static Document documentAt(int index) {
        GitHubSearchResponse.Repository repository = response.items().get(index);
        return new GitHubNormalizer()
                .normalize(new GitHubSearchResponse.Raw(
                        repository, "github", String.valueOf(repository.id()), provenance()));
    }

    private static Provenance provenance() {
        return new Provenance(
                "github",
                Instant.parse("2026-03-01T10:00:00Z"),
                "https://api.github.com/search/repositories?q=speculative+decoding",
                200,
                "d".repeat(64),
                null);
    }

    @Test
    @DisplayName("разбирается выдача поиска со счётчиком")
    void theSearchResultAndItsCounterAreParsed() {
        assertThat(response.items()).hasSize(2);
        assertThat(response.totalCount()).isEqualTo(63);
    }

    @Test
    @DisplayName("дата — создание репозитория, а не последний коммит")
    void theDateIsCreationNotTheLastPush() {
        // В образце разница больше трёх лет намеренно: иначе тест не отличил бы верный выбор от
        // совпадения.
        assertThat(documentAt(0).publishedOn()).isEqualTo(LocalDate.of(2022, 12, 5));
    }

    @Test
    @DisplayName("темы репозитория становятся предметными кодами")
    void repositoryTopicsBecomeSubjectCodes() {
        assertThat(documentAt(0).topics())
                .extracting(DocumentTopic::code)
                .contains("speculative-decoding", "llm-inference", "transformers");
    }

    @Test
    @DisplayName("владелец-организация записывается как организация")
    void anOrganisationOwnerCountsAsAnOrganisation() {
        assertThat(documentAt(0).authors()).extracting("organizationName").contains("huggingface");
    }

    @Test
    @DisplayName("личный аккаунт организацией не считается")
    void aPersonalAccountIsNotAnOrganisation() {
        // Правило достоверности требует двух независимых организаций. Засчитать личный аккаунт —
        // значит подтвердить тему двумя энтузиастами и подать её как индустриальный сигнал.
        assertThat(documentAt(1).authors()).extracting("organizationName").containsOnlyNulls();
    }

    @Test
    @DisplayName("звёзды и форки сохраняются сырыми")
    void starsAndForksAreKeptRaw() {
        assertThat(documentAt(0).metrics().stars()).isEqualTo(2841);
        assertThat(documentAt(0).metrics().forks()).isEqualTo(214);
    }

    @Test
    @DisplayName("репозиторий без описания, тем и лицензии разбирается")
    void aBareRepositoryStillParses() {
        // Свежий репозиторий обычно именно такой — и он же самый ранний сигнал.
        Document document = documentAt(1);

        assertThat(document.title()).contains("draft-verify-toy");
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(document.topics()).isEmpty();
    }
}
