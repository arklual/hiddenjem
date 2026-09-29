package dev.horizon.ingestion.domain.document;

import java.util.Locale;

import dev.horizon.platform.common.util.Guards;

/**
 * An author of a document together with the affiliation reported by the source.
 *
 * <p>Affiliation is kept on the author rather than on the document because the diffusion indicator
 * counts <em>distinct organisations</em> behind a topic, and a paper routinely spans several.
 */
public record Author(
        String fullName,
        String orcid,
        String organizationName,
        OrganizationType organizationType,
        String organizationCountry) {

    public static final int MAX_NAME_LENGTH = 300;

    public Author {
        fullName = Guards.requireText(fullName, "author.fullName").trim();
        if (fullName.length() > MAX_NAME_LENGTH) {
            fullName = fullName.substring(0, MAX_NAME_LENGTH);
        }
        orcid = normalizeOrcid(orcid);
        organizationName = truncate(blankToNull(organizationName), 400);
        organizationCountry = normalizeCountry(organizationCountry);
    }

    public static Author of(String fullName) {
        return new Author(fullName, null, null, null, null);
    }

    /** ORCID is stored bare ({@code 0000-0002-1825-0097}); the resolver prefix carries no information. */
    private static String normalizeOrcid(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String orcid = value.trim();
        int marker = orcid.lastIndexOf("orcid.org/");
        if (marker >= 0) {
            orcid = orcid.substring(marker + "orcid.org/".length());
        }
        orcid = orcid.trim().toUpperCase(Locale.ROOT);
        return orcid.isBlank() ? null : truncate(orcid, 24);
    }

    /** ISO 3166-1 alpha-2, upper case — the column is {@code char(2)}. */
    private static String normalizeCountry(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String country = value.trim().toUpperCase(Locale.ROOT);
        return country.length() == 2 ? country : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
