package dev.horizon.ingestion.domain.document;

import dev.horizon.platform.common.util.Guards;

/** Journal, conference or repository a document was published in. */
public record Venue(String name, String type, String issn) {

    public Venue {
        name = Guards.requireText(name, "venue.name").trim();
        if (name.length() > 300) {
            name = name.substring(0, 300);
        }
        type = blankToNull(type);
        if (type != null && type.length() > 24) {
            type = type.substring(0, 24);
        }
        issn = blankToNull(issn);
    }

    public static Venue named(String name) {
        return new Venue(name, null, null);
    }

    /** {@code null}-tolerant factory: many records simply have no venue. */
    public static Venue orNull(String name, String type, String issn) {
        return name == null || name.isBlank() ? null : new Venue(name, type, issn);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
