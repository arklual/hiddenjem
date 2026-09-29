package dev.horizon.ingestion.connector.arxiv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Разбор подлинного ответа arXiv и его отображение в документ.
 *
 * <p>Слой, где внешний мир превращается в модель, до сих пор не проверялся ничем: семь
 * нормализаторов и ни одного теста. Ломается он молча — источник меняет имя поля, разбор возвращает
 * пустое значение вместо исключения, и документы продолжают поступать, только без авторов, без даты
 * или без предметных кодов. Отчёт при этом собирается, выглядит целым и врёт.
 *
 * <p>Ответ в `src/test/resources/connector/arxiv/query-response.xml` записан как его отдаёт arXiv:
 * с пространствами имён, порядком элементов и форматом дат. Подогнать его под удобство разбора
 * значило бы проверять собственную выдумку — а вопрос, ради которого этот тест существует, звучит
 * «работает ли оно на настоящих данных».
 */
class ArxivFeedTest {

    private static ArxivFeedParser.Feed feed;

    @BeforeAll
    static void parseRealResponse() throws IOException {
        try (InputStream stream = ArxivFeedTest.class.getResourceAsStream("/connector/arxiv/query-response.xml")) {
            assertThat(stream).as("записанный ответ arXiv").isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            feed = new ArxivFeedParser().parse("arxiv", xml, provenance());
        }
    }

    private static Provenance provenance() {
        return new Provenance(
                "arxiv",
                Instant.parse("2026-03-01T10:00:00Z"),
                "http://export.arxiv.org/api/query?search_query=all:speculative+decoding",
                200,
                "a".repeat(64),
                null);
    }

    @Test
    @DisplayName("разбираются все записи ленты")
    void everyEntryIsParsed() {
        assertThat(feed.entries()).hasSize(2);
        assertThat(feed.totalResults()).isEqualTo(137);
        assertThat(feed.startIndex()).isZero();
    }

    @Test
    @DisplayName("предметные коды доходят до документа")
    void subjectCodesReachTheDocument() {
        // Самое дорогое место. Отнесение темы к направлению держится именно на этих кодах
        // (методология §12): без них фильтр направления вырождается в лексическую догадку, а отчёт
        // по безопасности наполняется темами по ИИ. Потеря кодов не роняет ничего и не видна.
        Document document = new ArxivNormalizer().normalize(feed.entries().get(0));

        assertThat(document.topics()).extracting(DocumentTopic::code).contains("cs.LG", "cs.CL");
    }

    @Test
    @DisplayName("дата публикации берётся из published, а не из updated")
    void theDateIsThePublicationDateNotTheRevision() {
        // Год первого упоминания — вход индикатора новизны. Взять updated значило бы состарить или
        // омолодить тему на годы: у первой записи разница между published и updated — полгода.
        Document document = new ArxivNormalizer().normalize(feed.entries().get(0));

        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2022, 11, 30));
    }

    @Test
    @DisplayName("авторы и их организации разбираются")
    void authorsAndTheirOrganisationsAreParsed() {
        // Организации — вход индикатора диффузии и правила достоверности: тема считается
        // подтверждённой, если о ней пишут две независимые организации.
        Document document = new ArxivNormalizer().normalize(feed.entries().get(0));

        assertThat(document.authors()).extracting("fullName").contains("Yaniv Leviathan", "Matan Kalman");
        assertThat(document.authors()).extracting("organizationName").contains("Google Research");
    }

    @Test
    @DisplayName("запись без необязательных полей разбирается, а не отбрасывается")
    void anEntryWithoutOptionalFieldsStillParses() {
        // Вторая запись без DOI, без журнала и без аффилиаций — обычный случай для препринта.
        // Отбросить её значило бы потерять именно самые ранние публикации, ради которых всё и
        // строится.
        Document document = new ArxivNormalizer().normalize(feed.entries().get(1));

        assertThat(document.title()).contains("Speculative Sampling");
        assertThat(document.publishedOn()).isEqualTo(LocalDate.of(2023, 2, 2));
        assertThat(document.topics()).extracting(DocumentTopic::code).contains("cs.CL");
    }

    @Test
    @DisplayName("заголовок и аннотация очищены от переносов ленты")
    void theTitleAndAbstractAreCleanedOfFeedWrapping() {
        // arXiv переносит строки внутри summary и добавляет отступы. Оставить их значит внести в
        // текст мусор, по которому потом извлекаются термины.
        Document document = new ArxivNormalizer().normalize(feed.entries().get(0));

        assertThat(document.title()).doesNotContain("\n");
        assertThat(document.abstractText()).doesNotStartWith(" ");
    }
}
