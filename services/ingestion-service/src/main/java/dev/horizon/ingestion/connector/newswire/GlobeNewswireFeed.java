package dev.horizon.ingestion.connector.newswire;

import java.io.StringReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import dev.horizon.ingestion.connector.support.ConnectorException;

/**
 * Разбор ленты GlobeNewswire по ключевому слову.
 *
 * <p><b>Почему не общий {@code SearchFeeds}.</b> Главное в этой ленте — {@code dc:contributor}, имя
 * выпустившей релиз компании, — объявлено под нестандартным адресом пространства имён
 * ({@code http://dublincore.org/documents/dcmi-namespace/} вместо
 * {@code http://purl.org/dc/elements/1.1/}), и модуль Dublin Core библиотеки Rome его не видит.
 * Без выпустившей компании релиз теряет то, ради чего заведён: организацию для правила двух
 * независимых свидетельств. Поэтому лента читается напрямую, по именам элементов.
 *
 * <p>Дата — {@code pubDate} без секунд ({@code Tue, 15 Sep 2026 08:02 GMT}); у RFC 1123 секунды
 * обязательны, поэтому шаблон свой, с необязательными секундами.
 */
final class GlobeNewswireFeed {

    private static final DateTimeFormatter PUB_DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] zzz", Locale.ENGLISH);

    /** Запись ленты: всё, что нужно документу, и ничего от XML. */
    record Item(
            String identifier,
            String title,
            String link,
            String description,
            Instant publishedAt,
            String contributor,
            String language) {}

    private GlobeNewswireFeed() {}

    /**
     * @throws ConnectorException.Permanent тело — не RSS: площадка ответила страницей-заглушкой
     *     вместо ленты. Это отказ, а не ноль релизов.
     */
    static List<Item> parse(String sourceId, String url, String body) {
        org.w3c.dom.Document xml;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Недоверенный XML из открытой сети: без DTD и внешних сущностей.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            xml = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body == null ? "" : body)));
        } catch (ParserConfigurationException | SAXException | java.io.IOException e) {
            throw new ConnectorException.Permanent(sourceId, 200, "Cannot parse GlobeNewswire feed " + url, e);
        }
        Element root = xml.getDocumentElement();
        if (root == null || !"rss".equals(root.getTagName())) {
            throw new ConnectorException.Permanent(
                    sourceId,
                    200,
                    "GlobeNewswire answered " + url + " with <" + (root == null ? "" : root.getTagName())
                            + "> instead of an RSS feed");
        }
        NodeList nodes = root.getElementsByTagName("item");
        List<Item> items = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String link = text(item, "link");
            if (link == null) {
                link = text(item, "guid");
            }
            String identifier = text(item, "dc:identifier");
            items.add(new Item(
                    identifier != null ? identifier : link,
                    HtmlText.plain(text(item, "title")),
                    link,
                    text(item, "description"),
                    date(text(item, "pubDate")),
                    HtmlText.plain(text(item, "dc:contributor")),
                    text(item, "dc:language")));
        }
        return items;
    }

    static Instant date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.trim(), PUB_DATE).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String text(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && tag.equals(element.getTagName())) {
                String value = element.getTextContent();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
