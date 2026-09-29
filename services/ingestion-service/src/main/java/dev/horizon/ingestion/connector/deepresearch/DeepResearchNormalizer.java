package dev.horizon.ingestion.connector.deepresearch;

import dev.horizon.ingestion.connector.deepresearch.model.ResearchPage;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Прочитанная агентом страница → документ корпуса.
 *
 * <p>Текст документа — отрывки страницы, как их получил сервис моделей; имена, которые страница
 * подтвердила, в документ не пишутся — ни в текст, ни в рубрики. Рубрика «названо моделью»
 * записала бы документ в направление по слову модели, а извлечение терминов обязано найти имя в
 * тексте само, как у любого другого документа.
 *
 * <p>Организация — та, что назвал каталог (владелец-организация репозитория, издание, компания блога
 * на Хабре), а для страницы без авторов — её сайт. Десять страниц одного сайта остаются одной
 * организацией, и BRULE-1 считает их одним свидетельством.
 */
public class DeepResearchNormalizer implements DocumentNormalizer<ResearchPage> {

    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(ResearchPage page) {
        SourceClass sourceClass = SourceClass.valueOf(page.sourceClass());
        String excerpt = page.excerpt();
        if (excerpt != null && excerpt.length() > MAX_ABSTRACT_LENGTH) {
            excerpt = excerpt.substring(0, MAX_ABSTRACT_LENGTH);
        }
        var builder = Document.builder()
                .externalRef(page.sourceId(), page.externalId())
                .sourceClass(sourceClass)
                .title(page.title())
                .abstractText(excerpt)
                .language(page.language())
                .publishedOn(page.publishedOn())
                .url(page.url())
                .doi(page.doi())
                .arxivId(page.arxivId())
                .provenance(page.provenance());
        String venue = page.venue() != null ? page.venue() : sourceClass == SourceClass.NEWS ? page.host() : null;
        builder.venue(Venue.orNull(venue, sourceClass == SourceClass.NEWS ? "NEWS_OUTLET" : null, null));
        OrganizationType type = page.organizationIsCompany() ? OrganizationType.COMPANY : null;
        for (String name : page.authors()) {
            builder.author(new Author(name, null, page.organization(), page.organization() == null ? null : type, null));
        }
        return builder.build();
    }
}
