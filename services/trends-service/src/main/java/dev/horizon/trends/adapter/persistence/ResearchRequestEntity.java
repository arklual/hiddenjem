package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code trends.research_requests}.
 *
 * <p>Mutable and versioned, unlike the report entities: a research request is a long-running process
 * whose state legitimately changes, and {@link Version} turns a lost update into a detectable
 * {@code OptimisticLockException} instead of a silently overwritten saga step. The pessimistic lock
 * used by the saga (see {@code ResearchRequestJpaRepository#findByIdForUpdate}) and this optimistic
 * version are complementary: the former serialises concurrent saga steps, the latter protects the
 * paths that legitimately read without locking (cancel from the API, timeout sweeper).
 *
 * <p>This class is an anaemic persistence record on purpose. All behaviour lives in the aggregate;
 * mixing the two would force the domain to inherit JPA's constraints (no-arg constructor, mutable
 * fields, proxy-friendly non-final types) — exactly the coupling the hexagonal split exists to avoid.
 */
@Entity
@Table(name = "research_requests")
public class ResearchRequestEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "raw_query", nullable = false, length = 200)
    private String rawQuery;

    @Column(name = "normalized_query", nullable = false, length = 200)
    private String normalizedQuery;

    @Column(name = "query_language", length = 2)
    private String queryLanguage;

    @Column(name = "top_n", nullable = false)
    private short topN;

    @Column(name = "years_window", nullable = false)
    private short yearsWindow;

    /**
     * Postgres {@code varchar(24)[]}. Hibernate 6 maps a Java array of a basic type onto the
     * dialect's native array type, so no converter and no join table are needed for what is
     * semantically a small unordered set.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_classes", nullable = false)
    private String[] sourceClasses = new String[0];

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence;

    @Column(name = "include_mature", nullable = false)
    private boolean includeMature;

    @Column(name = "methodology_profile_id", nullable = false)
    private UUID methodologyProfileId;

    /**
     * Режим анализа именем на проводе, а не перечислением: строка, записанная новее кода, обязана
     * читаться.
     *
     * <p>Колонка {@code engine} здесь больше не отображается: движок один, её умолчание ({@code
     * signals}, V21) подписывает новые строки, а прежние значения остаются в базе историей.
     */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "params_discriminator", nullable = false, length = 160)
    private String paramsDiscriminator;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "progress_stage", length = 24)
    private String progressStage;

    @Column(name = "progress_percent", nullable = false)
    private short progressPercent;

    @Column(name = "progress_message", length = 300)
    private String progressMessage;

    @Column(name = "progress_updated_at", nullable = false)
    private Instant progressUpdatedAt;

    @Column(name = "corpus_snapshot_id")
    private UUID corpusSnapshotId;

    @Column(name = "corpus_document_count", nullable = false)
    private int corpusDocumentCount;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "corpus_sources_used", nullable = false)
    private String[] corpusSourcesUsed = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "corpus_unavailable", nullable = false)
    private String[] corpusUnavailable = new String[0];

    @Column(name = "analysis_job_id")
    private UUID analysisJobId;

    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "partial", nullable = false)
    private boolean partial;

    @Column(name = "failure_code", length = 48)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "failure_retryable")
    private Boolean failureRetryable;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @Column(name = "attempt", nullable = false)
    private short attempt;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ResearchRequestEntity() {
        // for JPA
    }

    public ResearchRequestEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public void setRawQuery(String rawQuery) {
        this.rawQuery = rawQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public void setNormalizedQuery(String normalizedQuery) {
        this.normalizedQuery = normalizedQuery;
    }

    public String getQueryLanguage() {
        return queryLanguage;
    }

    public void setQueryLanguage(String queryLanguage) {
        this.queryLanguage = queryLanguage;
    }

    public short getTopN() {
        return topN;
    }

    public void setTopN(short topN) {
        this.topN = topN;
    }

    public short getYearsWindow() {
        return yearsWindow;
    }

    public void setYearsWindow(short yearsWindow) {
        this.yearsWindow = yearsWindow;
    }

    public String[] getSourceClasses() {
        return sourceClasses == null ? new String[0] : sourceClasses.clone();
    }

    public void setSourceClasses(String[] sourceClasses) {
        this.sourceClasses = sourceClasses == null ? new String[0] : sourceClasses.clone();
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public boolean isIncludeMature() {
        return includeMature;
    }

    public void setIncludeMature(boolean includeMature) {
        this.includeMature = includeMature;
    }

    public UUID getMethodologyProfileId() {
        return methodologyProfileId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setMethodologyProfileId(UUID methodologyProfileId) {
        this.methodologyProfileId = methodologyProfileId;
    }

    public String getParamsDiscriminator() {
        return paramsDiscriminator;
    }

    public void setParamsDiscriminator(String paramsDiscriminator) {
        this.paramsDiscriminator = paramsDiscriminator;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProgressStage() {
        return progressStage;
    }

    public void setProgressStage(String progressStage) {
        this.progressStage = progressStage;
    }

    public short getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(short progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getProgressMessage() {
        return progressMessage;
    }

    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }

    public Instant getProgressUpdatedAt() {
        return progressUpdatedAt;
    }

    public void setProgressUpdatedAt(Instant progressUpdatedAt) {
        this.progressUpdatedAt = progressUpdatedAt;
    }

    public UUID getCorpusSnapshotId() {
        return corpusSnapshotId;
    }

    public void setCorpusSnapshotId(UUID corpusSnapshotId) {
        this.corpusSnapshotId = corpusSnapshotId;
    }

    public int getCorpusDocumentCount() {
        return corpusDocumentCount;
    }

    public void setCorpusDocumentCount(int corpusDocumentCount) {
        this.corpusDocumentCount = corpusDocumentCount;
    }

    public String[] getCorpusSourcesUsed() {
        return corpusSourcesUsed == null ? new String[0] : corpusSourcesUsed.clone();
    }

    public void setCorpusSourcesUsed(String[] corpusSourcesUsed) {
        this.corpusSourcesUsed = corpusSourcesUsed == null ? new String[0] : corpusSourcesUsed.clone();
    }

    public String[] getCorpusUnavailable() {
        return corpusUnavailable == null ? new String[0] : corpusUnavailable.clone();
    }

    public void setCorpusUnavailable(String[] corpusUnavailable) {
        this.corpusUnavailable = corpusUnavailable == null ? new String[0] : corpusUnavailable.clone();
    }

    public UUID getAnalysisJobId() {
        return analysisJobId;
    }

    public void setAnalysisJobId(UUID analysisJobId) {
        this.analysisJobId = analysisJobId;
    }

    public UUID getReportId() {
        return reportId;
    }

    public void setReportId(UUID reportId) {
        this.reportId = reportId;
    }

    public boolean isPartial() {
        return partial;
    }

    public void setPartial(boolean partial) {
        this.partial = partial;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public Boolean getFailureRetryable() {
        return failureRetryable;
    }

    public void setFailureRetryable(Boolean failureRetryable) {
        this.failureRetryable = failureRetryable;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public short getAttempt() {
        return attempt;
    }

    public void setAttempt(short attempt) {
        this.attempt = attempt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
