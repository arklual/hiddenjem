package dev.horizon.ingestion.connector.habr;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.habr.model.HabrPost;
import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Публикация Хабра → документ.
 *
 * <p><b>Блог компании и личный блог — разные свидетельства.</b> ТЗ относит к доверенным «официальные
 * сайты компаний-разработчиков» и к пониженной доверенности — «личные блоги». На Хабре живут оба, и
 * различает их адрес: {@code /ru/companies/<компания>/articles/…} — корпоративный блог. У такой
 * публикации организация — компания; правило «две независимые организации» и оценка доверенности
 * видят её так же, как аффилиацию у статьи. У личной публикации организации нет.
 *
 * <p><b>Рубрик нет намеренно.</b> Хабы и теги Хабра — русские метки, которых нет в перекрёстном
 * словаре направлений. Документ с метками, не совпавшими с направлением, считается размеченным
 * чужим направлением и снижает долю каждой своей темы (разбор 80); без меток его отнесение к
 * направлению выводится по соседству с размеченными документами (разбор 101).
 */
public class HabrNormalizer implements DocumentNormalizer<HabrPost> {

    private static final Pattern COMPANY_BLOG = Pattern.compile("habr\\.com/(?:ru|en)/companies/([^/]+)/");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_ABSTRACT_LENGTH = 4000;

    @Override
    public Document normalize(HabrPost post) {
        SearchFeeds.Entry entry = post.entry();
        if (entry.publishedAt() == null) {
            throw new IllegalArgumentException("Habr post without a publication date: " + entry.link());
        }
        String company = companyOf(entry.link());
        var builder = Document.builder()
                .externalRef(post.sourceId(), post.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(clean(entry.title()))
                .abstractText(truncate(clean(entry.description())))
                .language("ru")
                .publishedOn(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))
                .url(entry.link())
                .venue(new Venue(company == null ? "Хабр" : "Хабр, блог компании " + company, "BLOG", null))
                .provenance(post.provenance());
        for (String author : entry.authors()) {
            builder.author(
                    company == null
                            ? Author.of(author)
                            : new Author(author, null, company, OrganizationType.COMPANY, null));
        }
        if (entry.authors().isEmpty()) {
            builder.author(
                    company == null
                            ? Author.of("Хабр")
                            : new Author(company, null, company, OrganizationType.COMPANY, null));
        }
        return builder.build();
    }

    /** Компания корпоративного блога по адресу публикации; {@code null} — личная публикация. */
    static String companyOf(String link) {
        if (link == null) {
            return null;
        }
        Matcher matcher = COMPANY_BLOG.matcher(link);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = HTML_TAG.matcher(value)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        // Анонс Хабра кончается ссылкой «Читать далее» — это не текст публикации.
        text = text.replace("Читать далее", " ").replace("Read more", " ");
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ABSTRACT_LENGTH ? value : value.substring(0, MAX_ABSTRACT_LENGTH);
    }
}
