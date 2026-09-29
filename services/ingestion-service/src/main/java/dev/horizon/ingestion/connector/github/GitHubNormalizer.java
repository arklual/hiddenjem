package dev.horizon.ingestion.connector.github;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.horizon.ingestion.connector.github.model.GitHubSearchResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL for GitHub.
 *
 * <p>Decisions worth stating:
 *
 * <ul>
 *   <li><b>{@code created_at}, not {@code pushed_at}</b>, is the publication date: the emergence
 *       question is when the technology first appeared, and every active repository would otherwise
 *       report "today".
 *   <li><b>The programming language is a topic, not a language.</b> {@code documents.language} is
 *       ISO 639-1 for natural language; writing "Python" there would be a category error that
 *       silently corrupts every language filter downstream.
 *   <li><b>Stars and forks are metrics, not scores.</b> They are recorded raw and interpreted by the
 *       methodology, which is where the weighting belongs.
 *   <li><b>An organisation owner is a {@code COMPANY}</b>; a personal account has no organisation at
 *       all rather than a fabricated one.
 * </ul>
 */
public class GitHubNormalizer implements DocumentNormalizer<GitHubSearchResponse.Raw> {

    @Override
    public Document normalize(GitHubSearchResponse.Raw raw) {
        GitHubSearchResponse.Repository repository = raw.repository();
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(SourceClass.CODE_REPOSITORY)
                .title(titleOf(repository))
                .abstractText(repository.description())
                .publishedOn(createdOn(repository))
                .url(urlOf(repository))
                .venue(new Venue("GitHub", "CODE_HOSTING", null))
                .metrics(metricsOf(repository))
                .provenance(raw.provenance());

        var owner = repository.owner();
        if (owner != null && owner.login() != null) {
            boolean organization = "Organization".equalsIgnoreCase(owner.type());
            builder.author(new Author(
                    owner.login(),
                    null,
                    organization ? owner.login() : null,
                    organization ? OrganizationType.COMPANY : null,
                    null));
        }
        if (repository.language() != null && !repository.language().isBlank()) {
            builder.topic(DocumentTopic.of("lang:" + repository.language(), repository.language(), null));
        }
        for (String topic : repository.topics()) {
            builder.topic(DocumentTopic.of(topic, topic, null));
        }
        return builder.build();
    }

    private static DocumentMetrics metricsOf(GitHubSearchResponse.Repository repository) {
        Map<String, Number> extra = new LinkedHashMap<>();
        if (repository.openIssuesCount() != null) {
            extra.put("openIssues", repository.openIssuesCount());
        }
        return DocumentMetrics.ofRepository(repository.stargazersCount(), repository.forksCount(), extra);
    }

    private static String titleOf(GitHubSearchResponse.Repository repository) {
        String title = repository.fullName() == null || repository.fullName().isBlank()
                ? repository.name()
                : repository.fullName();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("GitHub repository without a name");
        }
        return title;
    }

    private static String urlOf(GitHubSearchResponse.Repository repository) {
        if (repository.htmlUrl() != null && !repository.htmlUrl().isBlank()) {
            return repository.htmlUrl();
        }
        return "https://github.com/" + titleOf(repository);
    }

    private static LocalDate createdOn(GitHubSearchResponse.Repository repository) {
        if (repository.createdAt() == null || repository.createdAt().isBlank()) {
            throw new IllegalArgumentException("GitHub repository without a creation date: " + repository.fullName());
        }
        return LocalDate.ofInstant(Instant.parse(repository.createdAt().trim()), ZoneOffset.UTC);
    }
}
