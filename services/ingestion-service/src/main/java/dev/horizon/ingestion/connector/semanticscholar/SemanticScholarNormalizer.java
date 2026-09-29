package dev.horizon.ingestion.connector.semanticscholar;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import dev.horizon.ingestion.connector.semanticscholar.model.SemanticScholarResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL для Semantic Scholar.
 *
 * <ul>
 *   <li><b>Класс источника — по месту публикации.</b> Работа без площадки, у которой есть
 *       идентификатор arXiv, — препринт; остальное — публикация. Semantic Scholar сам размечает
 *       тип ({@code publicationTypes}), но у свежих препринтов это поле почти всегда пусто.
 *   <li><b>Аффилиаций нет.</b> Массовый поиск их не отдаёт, и выдумывать их по имени автора
 *       нельзя: правило «две независимые организации» считало бы то, чего источник не говорил.
 *   <li><b>Области знаний не сохраняются.</b> {@code fieldsOfStudy} — «Computer Science»,
 *       «Medicine»: для правила принадлежности к направлению это слишком грубо. Статья о speculative
 *       decoding с меткой «Computer Science» считалась размеченной «не в ИИ» и тянула вниз долю
 *       направления у каждой своей темы (разбор 89).
 *   <li><b>Дата — {@code publicationDate}, иначе 1 января года.</b> Работа без даты и года
 *       отвергается: без неё она не встанет ни в один ряд.
 * </ul>
 */
public class SemanticScholarNormalizer implements DocumentNormalizer<SemanticScholarResponse.Raw> {

    @Override
    public Document normalize(SemanticScholarResponse.Raw raw) {
        SemanticScholarResponse.Paper paper = raw.paper();
        if (paper.title() == null || paper.title().isBlank()) {
            throw new IllegalArgumentException("Semantic Scholar paper without a title: " + paper.paperId());
        }
        Map<String, Object> ids = paper.externalIds();
        String doi = text(ids.get("DOI"));
        String arxiv = text(ids.get("ArXiv"));
        boolean preprint = arxiv != null && (paper.venue() == null || paper.venue().isBlank()
                || paper.venue().equalsIgnoreCase("arXiv.org"));

        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(preprint ? SourceClass.PREPRINT : SourceClass.JOURNAL_ARTICLE)
                .title(paper.title().trim())
                .abstractText(paper.abstractText())
                .publishedOn(publishedOn(paper))
                .url(paper.url() == null || paper.url().isBlank()
                        ? "https://www.semanticscholar.org/paper/" + paper.paperId()
                        : paper.url())
                .metrics(DocumentMetrics.ofCitations(paper.citationCount()))
                .provenance(raw.provenance());
        if (doi != null) {
            builder.doi(doi);
        }
        if (arxiv != null) {
            builder.arxivId(arxiv);
        }
        if (paper.venue() != null && !paper.venue().isBlank()) {
            builder.venue(new Venue(paper.venue(), preprint ? "PREPRINT_SERVER" : "JOURNAL", null));
        }
        for (SemanticScholarResponse.PaperAuthor author : paper.authors()) {
            if (author.name() != null && !author.name().isBlank()) {
                builder.author(new Author(author.name().trim(), null, null, null, null));
            }
        }
        return builder.build();
    }

    private static LocalDate publishedOn(SemanticScholarResponse.Paper paper) {
        if (paper.publicationDate() != null && !paper.publicationDate().isBlank()) {
            try {
                return LocalDate.parse(paper.publicationDate().trim());
            } catch (DateTimeParseException ignored) {
                // Падаем на год ниже: формат даты у старых записей бывает неполным.
            }
        }
        if (paper.year() != null && paper.year() > 1900) {
            return LocalDate.of(paper.year(), 1, 1);
        }
        throw new IllegalArgumentException("Semantic Scholar paper without a date: " + paper.paperId());
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
