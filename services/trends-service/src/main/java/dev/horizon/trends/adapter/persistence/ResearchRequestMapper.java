package dev.horizon.trends.adapter.persistence;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.AnalysisProgress;
import dev.horizon.trends.domain.research.AnalysisStage;
import dev.horizon.trends.domain.research.CorpusCoverage;
import dev.horizon.trends.domain.research.FailureInfo;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/**
 * Translates between the {@link ResearchRequest} aggregate and its row.
 *
 * <p>Two directions, deliberately explicit rather than reflective: a mapping bug here is a silent
 * data-corruption bug, and generated mappers hide exactly the field-by-field decisions worth
 * reviewing. Both directions are covered by a round-trip test.
 *
 * <p>{@code toEntity} mutates an existing row object when one is supplied so that Hibernate's dirty
 * checking and {@code @Version} still see a managed instance — replacing the entity would either
 * detach it or reset the optimistic-lock version.
 */
@Component
public class ResearchRequestMapper {

    /** Unknown source-class names in old rows are dropped rather than throwing. */
    private static Set<SourceClass> parseSourceClasses(String[] raw) {
        if (raw == null || raw.length == 0) {
            return Set.of();
        }
        var parsed = EnumSet.noneOf(SourceClass.class);
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                parsed.add(SourceClass.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A class removed from the enum must not make an existing request unreadable.
            }
        }
        return parsed;
    }

    public ResearchRequest toDomain(ResearchRequestEntity entity) {
        var parameters = new AnalysisParameters(
                entity.getTopN(),
                entity.getYearsWindow(),
                parseSourceClasses(entity.getSourceClasses()),
                entity.getMinConfidence(),
                entity.isIncludeMature(),
                entity.getMethodologyProfileId(),
                // Колонка `engine` не читается: движок один, и запрос, записанный с `methodology`,
                // при повторе считается сигналами, как и любой новый.
                AnalysisMode.stored(entity.getMode()));

        var progress = new AnalysisProgress(
                entity.getProgressStage() == null
                        ? AnalysisStage.forStatus(ResearchStatus.valueOf(entity.getStatus()))
                        : AnalysisStage.valueOf(entity.getProgressStage()),
                entity.getProgressPercent(),
                entity.getProgressMessage(),
                entity.getProgressUpdatedAt());

        var coverage = new CorpusCoverage(
                entity.getCorpusDocumentCount(),
                Arrays.asList(entity.getCorpusSourcesUsed()),
                Arrays.asList(entity.getCorpusUnavailable()));

        FailureInfo failure = entity.getFailureCode() == null
                ? null
                : new FailureInfo(
                        entity.getFailureCode(),
                        entity.getFailureMessage(),
                        Boolean.TRUE.equals(entity.getFailureRetryable()));

        return new ResearchRequest(
                new ResearchRequestId(entity.getId()),
                new RequesterRef(entity.getUserId(), entity.getOrganizationId()),
                new TechnologyDomainQuery(entity.getRawQuery(), entity.getNormalizedQuery(), entity.getQueryLanguage()),
                parameters,
                entity.getIdempotencyKey(),
                entity.getSubmittedAt(),
                entity.getDeadlineAt(),
                ResearchStatus.valueOf(entity.getStatus()),
                progress,
                entity.getAttempt(),
                entity.getCorpusSnapshotId(),
                coverage,
                entity.getAnalysisJobId(),
                entity.getReportId() == null ? null : new TrendReportId(entity.getReportId()),
                entity.isPartial(),
                failure,
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getVersion());
    }

    public ResearchRequestEntity toEntity(ResearchRequest request, ResearchRequestEntity existing) {
        var entity = existing == null ? new ResearchRequestEntity(request.id().value()) : existing;

        entity.setUserId(request.requester().userId());
        entity.setOrganizationId(request.requester().organizationId());
        entity.setRawQuery(request.query().raw());
        entity.setNormalizedQuery(request.query().normalized());
        entity.setQueryLanguage(request.query().language());

        var parameters = request.parameters();
        entity.setTopN((short) parameters.topN());
        entity.setYearsWindow((short) parameters.yearsWindow());
        entity.setSourceClasses(sortedNames(parameters.sourceClasses()));
        entity.setMinConfidence(parameters.minConfidence());
        entity.setIncludeMature(parameters.includeMature());
        entity.setMethodologyProfileId(parameters.methodologyProfileId());
        entity.setMode(parameters.mode().wireName());
        // Ключ пишется один раз — при создании строки. Параметры запроса после приёма не меняются,
        // так что для новых строк это ничего не меняет; зато строка, записанная прежним движком,
        // сохраняет прежний ключ (`…|methodology`) и при каждом следующем сохранении — таймауте,
        // позднем результате — не перепишется в новый формат, под которым её отчёт выдавался бы
        // ответом на вопрос, заданный уже после вывода движка.
        if (entity.getParamsDiscriminator() == null) {
            entity.setParamsDiscriminator(parameters.cacheDiscriminator());
        }

        entity.setStatus(request.status().name());
        entity.setProgressStage(request.progress().stage().name());
        entity.setProgressPercent((short) request.progress().percent());
        entity.setProgressMessage(request.progress().message());
        entity.setProgressUpdatedAt(request.progress().updatedAt());

        entity.setCorpusSnapshotId(request.corpusSnapshotId().orElse(null));
        entity.setCorpusDocumentCount(request.corpusCoverage().documentCount());
        entity.setCorpusSourcesUsed(toArray(request.corpusCoverage().sourcesUsed()));
        entity.setCorpusUnavailable(toArray(request.corpusCoverage().unavailableSources()));

        entity.setAnalysisJobId(request.analysisJobId().orElse(null));
        entity.setReportId(request.reportId().map(TrendReportId::value).orElse(null));
        entity.setPartial(request.partial());

        request.failure()
                .ifPresentOrElse(
                        failure -> {
                            entity.setFailureCode(failure.code());
                            entity.setFailureMessage(failure.message());
                            entity.setFailureRetryable(failure.retryable());
                        },
                        () -> {
                            entity.setFailureCode(null);
                            entity.setFailureMessage(null);
                            entity.setFailureRetryable(null);
                        });

        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setAttempt((short) request.attempt());
        entity.setSubmittedAt(request.submittedAt());
        entity.setStartedAt(request.startedAt().orElse(null));
        entity.setFinishedAt(request.finishedAt().orElse(null));
        entity.setDeadlineAt(request.deadlineAt());
        return entity;
    }

    /** Sorted so the stored array is canonical — two equal parameter sets produce equal rows. */
    private static String[] sortedNames(Set<SourceClass> classes) {
        return classes.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    private static String[] toArray(List<String> values) {
        return new LinkedHashSet<>(values).toArray(String[]::new);
    }
}
