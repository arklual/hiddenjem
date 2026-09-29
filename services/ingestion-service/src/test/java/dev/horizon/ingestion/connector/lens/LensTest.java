package dev.horizon.ingestion.connector.lens;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.ResearchSourcesTestSupport;
import dev.horizon.ingestion.connector.lens.model.LensPatentResponse;
import dev.horizon.ingestion.connector.lens.model.LensScholarlyResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;

/**
 * Разбор ответов Lens на примерах записей из документации API (docs.api.lens.org, 29.09): научная
 * работа 2015 года и европейская заявка EP 2471949 A1. Поля оставлены те, что коннектор запрашивает.
 */
class LensTest {

    private static LensScholarlyResponse scholarly;
    private static LensPatentResponse patents;

    @BeforeAll
    static void parse() throws IOException {
        scholarly = ResearchSourcesTestSupport.read("/connector/lens/scholarly-response.json", LensScholarlyResponse.class);
        patents = ResearchSourcesTestSupport.read("/connector/lens/patent-response.json", LensPatentResponse.class);
    }

    private static Document work() {
        var work = scholarly.data().get(0);
        return new LensScholarlyNormalizer().normalize(new LensScholarlyResponse.Raw(
                work, "lens", work.lensId(), ResearchSourcesTestSupport.provenance("lens")));
    }

    private static Document patent() {
        var patent = patents.data().get(0);
        return new LensPatentNormalizer().normalize(new LensPatentResponse.Raw(
                patent, "lenspatents", patent.lensId(), ResearchSourcesTestSupport.provenance("lenspatents")));
    }

    @Test
    @DisplayName("научная работа: дата, DOI, цитирования и аффилиации доходят до документа")
    void scholarlyWorkIsNormalised() {
        Document document = work();

        assertThat(document.sourceClass()).isEqualTo(SourceClass.JOURNAL_ARTICLE);
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2015, 6, 13));
        assertThat(document.identifiers().doi()).isEqualTo("10.1016/j.ejca.2015.05.019");
        assertThat(document.identifiers().url()).isEqualTo("https://doi.org/10.1016/j.ejca.2015.05.019");
        assertThat(document.abstractText()).startsWith("Abstract Background");
        assertThat(document.authors()).hasSize(12);
        assertThat(document.authors().get(0).fullName()).isEqualTo("Maha Hussain");
        assertThat(document.authors().get(0).organizationName()).isEqualTo("University of Michigan");
        assertThat(document.authors().get(0).organizationType()).isEqualTo(OrganizationType.UNIVERSITY);
        assertThat(document.authors().get(0).organizationCountry()).isEqualTo("US");
    }

    @Test
    @DisplayName("патент: английское название, номер с ведомством и видом, заявитель — организация")
    void patentIsNormalised() {
        Document document = patent();

        assertThat(document.sourceClass()).isEqualTo(SourceClass.PATENT);
        assertThat(document.title()).startsWith("Method for the identification by molecular techniques");
        assertThat(document.identifiers().patentNumber()).isEqualTo("EP2471949A1");
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2012, 7, 4));
        assertThat(document.identifiers().url()).isEqualTo("https://lens.org/031-156-664-516-153");
        assertThat(document.venue().name()).isEqualTo("EP, заявка (A1)");
        assertThat(document.authors().get(0).fullName()).isEqualTo("Ochoa Jorge");
        assertThat(document.authors().get(0).organizationName()).isEqualTo("PROGENIKA BIOPHARMA SA");
        assertThat(document.authors().get(0).organizationType()).isEqualTo(OrganizationType.COMPANY);
        assertThat(document.topics()).isNotEmpty();
    }

    @Test
    @DisplayName("цели направления — фразами в кавычках через OR, без кодов классификатора")
    void queryJoinsTargetsWithOr() {
        assertThat(LensSearch.queryString(List.of("quantum sensing", "cs.LG", "nv\\center")))
                .isEqualTo("\"quantum sensing\" OR \"nv\\\\center\"");
    }

    @Test
    @DisplayName("тело запроса: поля, окно дат, смещение и проекция")
    void bodyCarriesTheWindowAndThePage() throws IOException {
        var mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(LensSearch.body(
                mapper, "\"quantum sensing\"", List.of("title", "abstract"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 29), 200, 100, List.of("lens_id")));

        JsonNode bool = body.path("query").path("bool");
        assertThat(bool.path("must").get(0).path("query_string").path("query").asText()).isEqualTo("\"quantum sensing\"");
        assertThat(bool.path("filter").get(0).path("range").path("date_published").path("gte").asText())
                .isEqualTo("2024-01-01");
        assertThat(body.path("from").asInt()).isEqualTo(200);
        assertThat(body.path("size").asInt()).isEqualTo(100);
        assertThat(body.path("include").get(0).asText()).isEqualTo("lens_id");
    }

    @Test
    @DisplayName("листание: неполная страница, конец выдачи и предел в десять тысяч записей")
    void pagingStopsWhereLensStops() {
        assertThat(LensSearch.next(0, 100, 100, 350)).isEqualTo("100");
        assertThat(LensSearch.next(300, 100, 50, 350)).isNull();
        assertThat(LensSearch.next(200, 100, 100, 300)).isNull();
        assertThat(LensSearch.size(9_950, 100)).isEqualTo(50);
        assertThat(LensSearch.next(9_950, 50, 50, 20_000)).isNull();
    }

    @Test
    @DisplayName("без ключа источник не настроен и называет переменную; патенты берут общий ключ")
    void withoutATokenTheSourceSaysWhatIsMissing() {
        var none = new ConnectorsProperties(null, null, null, null, 0, null, Map.of());
        var lens = new LensScholarlyConnector(none, null, new ObjectMapper());
        // Не «недоступен», а выключен: иначе каждый отчёт без ключа Lens считался бы неполным.
        assertThat(lens.descriptor().switchedOff()).isTrue();
        assertThat(lens.descriptor().unavailableReason()).contains("HORIZON_LENS_API_TOKEN");

        var settings = new ConnectorsProperties.ConnectorSettings(true, null, null, null, "token", null, null, null, null);
        var keyed = new ConnectorsProperties(null, null, null, null, 0, null, Map.of("lenspatents", settings));
        var patentsConnector = new LensPatentConnector(keyed, null, new ObjectMapper());
        assertThat(patentsConnector.descriptor().available()).isTrue();
        assertThat(patentsConnector.descriptor().primaryClass()).isEqualTo(SourceClass.PATENT);
    }
}
