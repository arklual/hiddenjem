package dev.horizon.ingestion.connector.semanticscholar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.connector.ResearchSourcesTestSupport;
import dev.horizon.ingestion.connector.semanticscholar.model.SemanticScholarResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;

/** Разбор подлинного ответа массового поиска Semantic Scholar (запрос «speculative decoding», 2026-09-18). */
class SemanticScholarTest {

    private static SemanticScholarResponse response;

    @BeforeAll
    static void parse() throws IOException {
        response = ResearchSourcesTestSupport.read(
                "/connector/semanticscholar/bulk-response.json", SemanticScholarResponse.class);
    }

    private static Document documentAt(int index) {
        var paper = response.data().get(index);
        return new SemanticScholarNormalizer().normalize(new SemanticScholarResponse.Raw(
                paper, "semanticscholar", paper.paperId(), ResearchSourcesTestSupport.provenance("semanticscholar")));
    }

    @Test
    @DisplayName("страница разбирается; без токена продолжения она последняя")
    void pageIsParsed() {
        // По запросу работ меньше тысячи — одной страницы массового поиска, и токена нет.
        assertThat(response.data()).hasSize(3);
        assertThat(response.total()).isPositive();
        assertThat(response.token()).isNull();
    }

    @Test
    @DisplayName("работа с arXiv без журнала — препринт, с конференцией — публикация")
    void sourceClassFollowsTheVenue() {
        assertThat(documentAt(0).sourceClass()).isEqualTo(SourceClass.PREPRINT);
        assertThat(documentAt(1).sourceClass()).isEqualTo(SourceClass.JOURNAL_ARTICLE);
    }

    @Test
    @DisplayName("аннотация, дата, DOI и авторы доходят до документа")
    void fieldsReachTheDocument() {
        Document document = documentAt(0);
        assertThat(document.abstractText()).isNotBlank();
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2025, 6, 7));
        assertThat(document.identifiers().arxivId()).isEqualTo("2506.06607");
        assertThat(document.authors()).isNotEmpty();
        assertThat(document.authors()).allSatisfy(author -> assertThat(author.organizationName()).isNull());
    }

    @Test
    @DisplayName("цели направления — через «|», без кодов классификатора arXiv")
    void queryJoinsTargetsWithOr() {
        assertThat(SemanticScholarConnector.query(List.of("machine learning", "cs.LG", "deep learning")))
                .isEqualTo("\"machine learning\" | \"deep learning\"");
    }
}
