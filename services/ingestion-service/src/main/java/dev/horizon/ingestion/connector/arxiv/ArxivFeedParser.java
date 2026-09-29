package dev.horizon.ingestion.connector.arxiv;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import dev.horizon.ingestion.connector.arxiv.model.ArxivEntry;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.domain.document.DocumentIdentifiers;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Parses arXiv's Atom response.
 *
 * <p>JDOM rather than Rome: arXiv's payload is Atom plus a private namespace
 * ({@code arxiv:doi}, {@code arxiv:journal_ref}, {@code arxiv:primary_category}), and those fields
 * are precisely the ones worth having. A generic feed abstraction would drop them.
 *
 * <p>The parser is hardened against XXE — DTDs are rejected and entities are not expanded. External
 * XML is untrusted input by definition, and a document-fetching service is exactly the kind of
 * target where an entity expansion would be handed a network path (NFR-S).
 */
public class ArxivFeedParser {

    private static final Namespace ATOM = Namespace.getNamespace("http://www.w3.org/2005/Atom");
    private static final Namespace ARXIV = Namespace.getNamespace("http://arxiv.org/schemas/atom");
    private static final Namespace OPENSEARCH = Namespace.getNamespace("http://a9.com/-/spec/opensearch/1.1/");

    /** Parsed feed: the entries plus the paging counters arXiv reports. */
    public record Feed(List<ArxivEntry> entries, int totalResults, int startIndex) {}

    public Feed parse(String sourceId, String xml, Provenance provenance) {
        Document document = read(sourceId, xml);
        Element root = document.getRootElement();
        int totalResults = intValue(root.getChildText("totalResults", OPENSEARCH), -1);
        int startIndex = intValue(root.getChildText("startIndex", OPENSEARCH), 0);

        List<ArxivEntry> entries = new ArrayList<>();
        for (Element entry : root.getChildren("entry", ATOM)) {
            ArxivEntry parsed = parseEntry(sourceId, entry, provenance);
            if (parsed != null) {
                entries.add(parsed);
            }
        }
        return new Feed(entries, totalResults, startIndex);
    }

    private ArxivEntry parseEntry(String sourceId, Element entry, Provenance provenance) {
        String id = text(entry.getChildText("id", ATOM));
        if (id == null) {
            return null;
        }
        String externalId = DocumentIdentifiers.normalizeArxivId(id);
        if (externalId == null) {
            return null;
        }

        List<ArxivEntry.ArxivAuthor> authors = new ArrayList<>();
        for (Element author : entry.getChildren("author", ATOM)) {
            String name = text(author.getChildText("name", ATOM));
            if (name != null) {
                authors.add(new ArxivEntry.ArxivAuthor(name, text(author.getChildText("affiliation", ARXIV))));
            }
        }

        List<String> categories = new ArrayList<>();
        for (Element category : entry.getChildren("category", ATOM)) {
            String term = text(category.getAttributeValue("term"));
            if (term != null) {
                categories.add(term);
            }
        }

        String absUrl = null;
        String pdfUrl = null;
        for (Element link : entry.getChildren("link", ATOM)) {
            String href = text(link.getAttributeValue("href"));
            String type = link.getAttributeValue("type");
            if (href == null) {
                continue;
            }
            if ("application/pdf".equals(type)) {
                pdfUrl = href;
            } else if ("text/html".equals(type) || absUrl == null) {
                absUrl = href;
            }
        }

        Element primary = entry.getChild("primary_category", ARXIV);
        return new ArxivEntry(
                id,
                text(entry.getChildText("title", ATOM)),
                text(entry.getChildText("summary", ATOM)),
                text(entry.getChildText("published", ATOM)),
                text(entry.getChildText("updated", ATOM)),
                authors,
                text(entry.getChildText("doi", ARXIV)),
                text(entry.getChildText("journal_ref", ARXIV)),
                primary == null ? null : text(primary.getAttributeValue("term")),
                categories,
                absUrl == null ? id : absUrl,
                pdfUrl,
                sourceId,
                externalId,
                provenance);
    }

    private Document read(String sourceId, String xml) {
        SAXBuilder builder = new SAXBuilder();
        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
        builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        builder.setExpandEntities(false);
        try {
            return builder.build(new StringReader(xml));
        } catch (JDOMException | java.io.IOException e) {
            throw new ConnectorException.Permanent(sourceId, 200, "arXiv returned unparsable XML", e);
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        // arXiv wraps titles and abstracts at column ~80; the newlines are formatting, not content.
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static int intValue(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
