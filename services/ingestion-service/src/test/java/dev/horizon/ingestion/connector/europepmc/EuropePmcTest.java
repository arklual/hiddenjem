package dev.horizon.ingestion.connector.europepmc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.connector.ResearchSourcesTestSupport;
import dev.horizon.ingestion.connector.europepmc.model.EuropePmcResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;

/**
 * Разбор подлинного ответа Europe PMC (запрос «federated learning», 2026-09-18).
 *
 * <p>Главное здесь — организация. Строка аффилиации «School of Computer, Nanjing University of
 * Posts and Telecommunications, Nanjing, 210023, China» целиком сделала бы каждую кафедру отдельной
 * организацией, и правило «две независимые организации» считало бы две кафедры одного вуза
 * независимыми подтверждениями.
 */
class EuropePmcTest {

    private static EuropePmcResponse response;

    @BeforeAll
    static void parse() throws IOException {
        response = ResearchSourcesTestSupport.read("/connector/europepmc/search-response.json", EuropePmcResponse.class);
    }

    private static Document documentOf(String source) {
        var result = response.results().stream().filter(item -> source.equals(item.source())).findFirst().orElseThrow();
        return new EuropePmcNormalizer().normalize(new EuropePmcResponse.Raw(
                result, "europepmc", result.source() + ":" + result.id(),
                ResearchSourcesTestSupport.provenance("europepmc")));
    }

    @Test
    @DisplayName("организация — учреждение из строки аффилиации, а не строка целиком")
    void institutionIsExtracted() {
        var result = response.results().get(1);
        var author = result.authorList().author().get(0);

        assertThat(EuropePmcNormalizer.institutionOf(author)).isEqualTo("Nanjing University of Posts and Telecommunications");
    }

    @Test
    @DisplayName("учреждение становится организацией автора с типом")
    void authorCarriesTheInstitution() {
        Document document = new EuropePmcNormalizer().normalize(new EuropePmcResponse.Raw(
                response.results().get(1), "europepmc", "MED:42660003",
                ResearchSourcesTestSupport.provenance("europepmc")));

        assertThat(document.authors().get(0).organizationName()).isEqualTo("Nanjing University of Posts and Telecommunications");
        assertThat(document.authors().get(0).organizationType()).isEqualTo(OrganizationType.UNIVERSITY);
    }

    @Test
    @DisplayName("источник PPR — препринт, MED — публикация")
    void preprintsAreRecognised() {
        assertThat(documentOf("PPR").sourceClass()).isEqualTo(SourceClass.PREPRINT);
        assertThat(documentOf("MED").sourceClass()).isEqualTo(SourceClass.JOURNAL_ARTICLE);
    }

    @Test
    @DisplayName("ссылка ведёт на запись Europe PMC, дата — первая публикация")
    void urlAndDate() {
        Document document = documentOf("MED");
        assertThat(document.identifiers().url()).startsWith("https://europepmc.org/article/MED/");
        assertThat(document.publishedOn()).isNotNull();
    }

    @Test
    @DisplayName("запрос — цели через OR и окно по дате первой публикации")
    void queryCarriesTermsAndWindow() {
        var request = new dev.horizon.ingestion.domain.port.CollectionRequest(
                java.util.UUID.randomUUID(),
                "технологии в искусственном интеллекте",
                "технологии в искусственном интеллекте",
                "ru",
                java.time.LocalDate.of(2020, 1, 1),
                java.time.LocalDate.of(2026, 9, 18),
                java.util.Set.of(),
                100,
                List.of("machine learning", "cs.LG", "deep learning"));

        assertThat(EuropePmcConnector.query(request))
                .isEqualTo("(\"machine learning\" OR \"deep learning\") AND FIRST_PDATE:[2020-01-01 TO 2026-09-18]");
    }
}
