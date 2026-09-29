package dev.horizon.ingestion.connector.producthunt;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.producthunt.model.ProductLaunch;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * Запуск на Product Hunt → документ.
 *
 * <p><b>Организация — сам продукт.</b> Автор записи в ленте — «охотник», опубликовавший запуск, и
 * часто это не создатель продукта; компанию-разработчика лента не называет. Поэтому автором
 * документа становится продукт как организация-компания: для правила двух независимых свидетельств
 * два разных продукта — две разные команды, а имя охотника ничего не говорит о том, кто вывел
 * технологию на рынок.
 *
 * <p>Текст — однострочный слоган продукта («AI buddy on your computer»): больше лента не даёт, и
 * терминам в нём сложиться почти не из чего. Это свидетельство выхода продукта с точной датой, а
 * не его описание.
 */
public class ProductHuntNormalizer implements DocumentNormalizer<ProductLaunch> {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Хвост каждой записи — ссылки «Discussion | Link», не текст о продукте. */
    private static final Pattern LINKS_TAIL = Pattern.compile("\\s*Discussion\\s*\\|\\s*Link\\s*$");

    @Override
    public Document normalize(ProductLaunch launch) {
        var entry = launch.entry();
        if (entry.publishedAt() == null) {
            throw new IllegalArgumentException("Product Hunt launch without a publication date: " + entry.link());
        }
        String product = clean(entry.title());
        return Document.builder()
                .externalRef(launch.sourceId(), launch.externalId())
                .sourceClass(SourceClass.NEWS)
                .title(product)
                .abstractText(tagline(entry.content() != null ? entry.content() : entry.description()))
                .language("en")
                .publishedOn(LocalDate.ofInstant(entry.publishedAt(), ZoneOffset.UTC))
                .url(entry.link())
                .venue(new Venue("Product Hunt", "PRODUCT_LAUNCH", null))
                .author(new Author(product, null, product, OrganizationType.COMPANY, null))
                .provenance(launch.provenance())
                .build();
    }

    /** Слоган без разметки и без ссылок «Discussion | Link». */
    static String tagline(String html) {
        String text = clean(html);
        if (text == null) {
            return null;
        }
        String tagline = LINKS_TAIL.matcher(text).replaceAll("").trim();
        return tagline.isEmpty() ? null : tagline;
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = TAG.matcher(value)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }
}
