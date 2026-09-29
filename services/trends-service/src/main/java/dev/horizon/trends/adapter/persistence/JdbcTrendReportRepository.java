package dev.horizon.trends.adapter.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;

import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.domain.report.Burst;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.Credibility;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.Exclusion;
import dev.horizon.trends.domain.report.ExplanationItem;
import dev.horizon.trends.domain.report.IndicatorScore;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.domain.report.MethodologyRef;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankStability;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.TimelinePoint;
import dev.horizon.trends.domain.report.TrendLocalization;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/**
 * Persists {@link TrendReport} with plain JDBC.
 *
 * <p>Why not JPA here, when everything else in this service uses it: a report is a deep, immutable
 * object graph written exactly once and read exactly once, whole. JPA's value — dirty checking,
 * identity map, lazy loading, optimistic locking — is all machinery for mutable state this aggregate
 * does not have, and it would cost an entity per node, a cascade configuration, and an N+1 problem on
 * read. Explicit SQL with batched child inserts is both simpler and faster for a write-once graph.
 *
 * <p>{@code indicators} and {@code timeline} are serialised into {@code jsonb} (data-model §3): they
 * are value objects that are never queried independently, so normalising them would create 15×6 +
 * 15×7 rows per report to serve no query. Evidence <em>is</em> a table because it is addressed on its
 * own. The {@code CAST(:x AS jsonb)} in the statements is what lets a plain {@code String} bind into
 * a jsonb column without a driver-specific {@code PGobject}, keeping this class portable.
 */
@Repository
public class JdbcTrendReportRepository implements TrendReportRepository {

    private static final TypeReference<List<IndicatorScore>> INDICATORS = new TypeReference<>() {};
    private static final TypeReference<List<TimelinePoint>> TIMELINE = new TypeReference<>() {};
    private static final TypeReference<List<Motivation.Attribution>> ATTRIBUTIONS = new TypeReference<>() {};
    private static final TypeReference<List<Exclusion>> EXCLUSIONS = new TypeReference<>() {};
    private static final TypeReference<TrendLocalization> LOCALIZATION = new TypeReference<>() {};
    private static final TypeReference<List<ExplanationItem>> EXPLANATION = new TypeReference<>() {};

    private static final String INSERT_REPORT =
            """
            INSERT INTO trend_reports (
                id, research_request_id, version, previous_version_id,
                raw_query, normalized_query, query_language,
                methodology_version, methodology_profile_id, score_aggregator, engine, mode,
                corpus_snapshot_id, window_from, window_to,
                documents_analyzed, candidates_evaluated,
                sources_used, unavailable_sources, partial, direction_recognized, direction_suggestions,
                suppressed_by_analyst, corpus_truncated, exclusions, truncated, generated_at)
            VALUES (
                :id, :researchRequestId, :version, :previousVersionId,
                :rawQuery, :normalizedQuery, :queryLanguage,
                :methodologyVersion, :methodologyProfileId, :scoreAggregator, :engine, :mode,
                :corpusSnapshotId, :windowFrom, :windowTo,
                :documentsAnalyzed, :candidatesEvaluated,
                :sourcesUsed, :unavailableSources, :partial, :directionRecognized, :directionSuggestions,
                :suppressedByAnalyst, :corpusTruncated, CAST(:exclusions AS jsonb), :truncated, :generatedAt)
            """;

    private static final String INSERT_TREND =
            """
            INSERT INTO report_trends (
                report_id, rank, trend_key, title, definition,
                problem_statement, benefit_statement, motivation_attribution,
                case_org_name, case_org_type, case_org_country, case_evidence_index, case_summary, case_basis,
                emergence_score, confidence, low_evidence, lifecycle_stage,
                first_mention_year, total_documents, burst_start_period, burst_weight,
                indicators, timeline, rank_stability_best, rank_stability_worst, direction_share,
                low_credibility_only, localization, explanation)
            VALUES (
                :reportId, :rank, :trendKey, :title, :definition,
                :problemStatement, :benefitStatement, CAST(:motivationAttribution AS jsonb),
                :caseOrgName, :caseOrgType, :caseOrgCountry, :caseEvidenceIndex, :caseSummary, :caseBasis,
                :emergenceScore, :confidence, :lowEvidence, :lifecycleStage,
                :firstMentionYear, :totalDocuments, :burstStartPeriod, :burstWeight,
                CAST(:indicators AS jsonb), CAST(:timeline AS jsonb),
                :rankStabilityBest, :rankStabilityWorst, :directionShare,
                :lowCredibilityOnly, CAST(:localization AS jsonb), CAST(:explanation AS jsonb))
            """;

    private static final String INSERT_EVIDENCE =
            """
            INSERT INTO report_trend_evidence (
                report_id, rank, ordinal, source_id, source_class, external_id, title,
                authors, organization, organization_country, published_on, url, doi,
                citation_count, relevance, snippet,
                language, credibility, credibility_basis, independent)
            VALUES (
                :reportId, :rank, :ordinal, :sourceId, :sourceClass, :externalId, :title,
                :authors, :organization, :organizationCountry, :publishedOn, :url, :doi,
                :citationCount, :relevance, :snippet,
                :language, :credibility, :credibilityBasis, :independent)
            """;

    private static final String SELECT_REPORT = "SELECT * FROM trend_reports WHERE id = :id";

    /**
     * The previous report of the same direction.
     *
     * <p>Driven from {@code research_requests}, because that is where the direction lives: the pair
     * {@code (normalized_query, params_discriminator)} is written there by the freshness rule, and
     * duplicating it onto the report would create a second definition of "the same question" that
     * drifts from the first the day the formula changes.
     *
     * <p>{@code status = 'COMPLETED'} is what lets the partial index {@code ix_requests_cache_lookup}
     * serve this, and it is repeated verbatim in {@code nextVersionFor} so the two agree on which
     * reports the direction contains.
     *
     * <p>Ordered by {@code version}, not by time: the version number exists precisely so that the
     * order of the history does not have to be recovered from timestamps, and choosing the
     * predecessor by time would reintroduce the dependency the number was meant to remove.
     */
    private static final String SELECT_LATEST_FOR_DIRECTION =
            """
            SELECT r.* FROM trend_reports r
            JOIN research_requests q ON q.id = r.research_request_id
            WHERE q.normalized_query = :normalizedQuery
              AND q.params_discriminator = :discriminator
              AND q.status = 'COMPLETED'
              AND q.id <> :excludingRequestId
            ORDER BY r.version DESC, r.id DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public JdbcTrendReportRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrendReport> findById(TrendReportId id) {
        return jdbc.query(SELECT_REPORT, new MapSqlParameterSource("id", id.value()), this::mapReport).stream()
                .findFirst()
                .map(this::withTrends);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrendReport> findLatestForDirection(
            String normalizedQuery, String paramsDiscriminator, ResearchRequestId excludingRequestId) {
        var parameters = new MapSqlParameterSource()
                .addValue("normalizedQuery", normalizedQuery)
                .addValue("discriminator", paramsDiscriminator)
                .addValue("excludingRequestId", excludingRequestId.value());
        return jdbc.query(SELECT_LATEST_FOR_DIRECTION, parameters, this::mapReport).stream()
                .findFirst()
                .map(this::withTrends);
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersionFor(String normalizedQuery, String paramsDiscriminator) {
        // Counted over the direction, so that two reports of the same question are never both
        // "version 1" and the history does not have to be reconstructed from timestamps.
        //
        // The same predicates as the lookup above, down to the status filter. Two queries that
        // decide "which reports belong to this direction" differently would hand out a version
        // number counted over one set and a predecessor chosen from another — the history would
        // have holes, and previous_version_id would stop meaning "version N minus one".
        Integer max = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(r.version), 0)
                FROM trend_reports r
                JOIN research_requests q ON q.id = r.research_request_id
                WHERE q.normalized_query = :normalizedQuery
                  AND q.params_discriminator = :discriminator
                  AND q.status = 'COMPLETED'
                """,
                new MapSqlParameterSource()
                        .addValue("normalizedQuery", normalizedQuery)
                        .addValue("discriminator", paramsDiscriminator),
                Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public Map<String, String> titlesByTrendKey(String normalizedQuery, Collection<String> trendKeys) {
        if (trendKeys.isEmpty()) {
            return Map.of();
        }
        // DISTINCT ON: у темы могло быть несколько названий в разных версиях отчёта, и показать надо
        // последнее — то, которое аналитик видел, когда ставил пометку.
        var sql =
                """
                SELECT DISTINCT ON (t.trend_key) t.trend_key, t.title
                FROM report_trends t
                JOIN trend_reports r ON r.id = t.report_id
                WHERE r.normalized_query = :normalizedQuery
                  AND t.trend_key IN (:trendKeys)
                ORDER BY t.trend_key, r.generated_at DESC
                """;
        var parameters = new MapSqlParameterSource()
                .addValue("normalizedQuery", normalizedQuery)
                .addValue("trendKeys", trendKeys);
        var titles = new LinkedHashMap<String, String>();
        jdbc.query(sql, parameters, rs -> {
            titles.put(rs.getString("trend_key"), rs.getString("title"));
        });
        return Map.copyOf(titles);
    }

    @Override
    public TrendReport save(TrendReport report) {
        jdbc.update(INSERT_REPORT, reportParameters(report));

        var trendBatch = new SqlParameterSource[report.trends().size()];
        var evidenceBatch = new ArrayList<SqlParameterSource>();
        for (int i = 0; i < report.trends().size(); i++) {
            var trend = report.trends().get(i);
            trendBatch[i] = trendParameters(report.id(), trend);
            for (int ordinal = 0; ordinal < trend.evidence().size(); ordinal++) {
                evidenceBatch.add(evidenceParameters(
                        report.id(), trend.rank(), ordinal, trend.evidence().get(ordinal)));
            }
        }
        // Batched: a 15-trend report with 20 sources each is 300 evidence rows — 300 round trips
        // would dominate the write latency budget of the assembly step.
        jdbc.batchUpdate(INSERT_TREND, trendBatch);
        jdbc.batchUpdate(INSERT_EVIDENCE, evidenceBatch.toArray(SqlParameterSource[]::new));
        return report;
    }

    // ── Parameter binding ────────────────────────────────────────────────────────────────────

    private SqlParameterSource reportParameters(TrendReport report) {
        return new MapSqlParameterSource()
                .addValue("id", report.id().value())
                .addValue("researchRequestId", report.researchRequestId().value())
                .addValue("version", report.version())
                .addValue(
                        "previousVersionId",
                        report.previousVersionId().map(TrendReportId::value).orElse(null),
                        Types.OTHER)
                .addValue("rawQuery", report.query().raw())
                .addValue("normalizedQuery", report.query().normalized())
                .addValue("queryLanguage", report.query().language())
                .addValue("methodologyVersion", report.methodology().version())
                .addValue("methodologyProfileId", report.methodology().profileId())
                .addValue("scoreAggregator", report.methodology().aggregator())
                .addValue("engine", report.methodology().engine())
                .addValue("mode", report.methodology().mode())
                .addValue("corpusSnapshotId", report.corpusSnapshotId())
                .addValue("windowFrom", report.coverage().windowFrom())
                .addValue("windowTo", report.coverage().windowTo())
                .addValue("documentsAnalyzed", report.coverage().documentsAnalyzed())
                .addValue("candidatesEvaluated", report.coverage().candidatesEvaluated())
                .addValue("sourcesUsed", report.coverage().sourcesUsed().toArray(String[]::new))
                .addValue(
                        "unavailableSources",
                        report.coverage().unavailableSources().toArray(String[]::new))
                .addValue("partial", report.coverage().partial())
                .addValue("directionRecognized", report.coverage().directionRecognized())
                .addValue(
                        "directionSuggestions",
                        report.coverage().directionSuggestions().toArray(String[]::new))
                .addValue("suppressedByAnalyst", report.coverage().suppressedByAnalyst())
                .addValue("corpusTruncated", report.coverage().corpusTruncated())
                .addValue("exclusions", json.write(report.coverage().exclusions()))
                .addValue("truncated", report.truncated())
                // Bound as OffsetDateTime, not Timestamp: the driver then writes an unambiguous
                // instant into timestamptz regardless of the JVM's default zone.
                .addValue("generatedAt", OffsetDateTime.ofInstant(report.generatedAt(), ZoneOffset.UTC));
    }

    private SqlParameterSource trendParameters(TrendReportId reportId, RankedTrend trend) {
        var caseExample = trend.caseExampleOptional();
        var burst = trend.burstOptional();
        return new MapSqlParameterSource()
                .addValue("reportId", reportId.value())
                .addValue("rank", (short) trend.rank())
                .addValue("trendKey", trend.trendKey())
                .addValue("title", trend.title())
                .addValue("definition", trend.definition())
                .addValue("problemStatement", trend.motivation().problem())
                .addValue("benefitStatement", trend.motivation().benefit())
                .addValue("motivationAttribution", json.write(trend.motivation().attributions()))
                .addValue(
                        "caseOrgName",
                        caseExample.map(CaseExample::organization).orElse(null))
                .addValue(
                        "caseOrgType",
                        caseExample
                                .map(CaseExample::organizationType)
                                .map(Enum::name)
                                .orElse(null))
                .addValue(
                        "caseOrgCountry", caseExample.map(CaseExample::country).orElse(null))
                .addValue(
                        "caseEvidenceIndex",
                        caseExample.map(c -> (short) c.evidenceIndex()).orElse(null),
                        Types.SMALLINT)
                .addValue("caseSummary", caseExample.map(CaseExample::summary).orElse(null))
                .addValue(
                        "caseBasis",
                        caseExample.map(CaseExample::basis).map(Enum::name).orElse(null))
                .addValue("emergenceScore", trend.assessment().score())
                .addValue("confidence", trend.assessment().confidence())
                .addValue(
                        "rankStabilityBest",
                        trend.assessment()
                                .rankStabilityOptional()
                                .map(RankStability::best)
                                .orElse(null))
                .addValue("directionShare", trend.directionShare())
                .addValue(
                        "rankStabilityWorst",
                        trend.assessment()
                                .rankStabilityOptional()
                                .map(RankStability::worst)
                                .orElse(null))
                .addValue("lowEvidence", trend.assessment().lowEvidence())
                .addValue("lifecycleStage", trend.lifecycleStage().name())
                .addValue("firstMentionYear", (short) trend.firstMentionYear())
                .addValue("totalDocuments", trend.totalDocuments())
                .addValue("burstStartPeriod", burst.map(Burst::startPeriod).orElse(null))
                .addValue("burstWeight", burst.map(Burst::weight).orElse(null), Types.NUMERIC)
                .addValue("indicators", json.write(trend.assessment().indicators()))
                .addValue("timeline", json.write(trend.timeline()))
                .addValue("lowCredibilityOnly", trend.lowCredibilityOnly())
                .addValue("localization", json.write(trend.localization()))
                .addValue("explanation", json.write(trend.explanation()));
    }

    private SqlParameterSource evidenceParameters(TrendReportId reportId, int rank, int ordinal, Evidence evidence) {
        return new MapSqlParameterSource()
                .addValue("reportId", reportId.value())
                .addValue("rank", (short) rank)
                .addValue("ordinal", (short) ordinal)
                .addValue("sourceId", evidence.sourceId())
                .addValue("sourceClass", evidence.sourceClass().name())
                // NOT NULL in the schema, but optional on the wire: an empty string preserves the
                // "no external identifier" fact without weakening the column.
                .addValue("externalId", evidence.externalId() == null ? "" : evidence.externalId())
                .addValue("title", evidence.title())
                .addValue("authors", evidence.authors())
                .addValue("organization", evidence.organization())
                .addValue("organizationCountry", evidence.organizationCountry())
                .addValue("publishedOn", evidence.publishedOn())
                .addValue("url", evidence.url())
                .addValue("doi", evidence.doi())
                .addValue("citationCount", evidence.citationCount(), Types.INTEGER)
                .addValue("relevance", evidence.relevance())
                .addValue("snippet", evidence.snippet())
                .addValue("language", evidence.language())
                .addValue("credibility", evidence.credibility().name())
                .addValue("credibilityBasis", evidence.credibilityBasis())
                .addValue("independent", evidence.independent());
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────────

    /** Intermediate carrier: the parent row is read before its children are known. */
    private record ReportHeader(
            TrendReportId id,
            ResearchRequestId researchRequestId,
            int version,
            TrendReportId previousVersionId,
            TechnologyDomainQuery query,
            MethodologyRef methodology,
            UUID corpusSnapshotId,
            Coverage coverage,
            boolean truncated,
            Instant generatedAt) {}

    private ReportHeader mapReport(ResultSet rs, int rowNumber) throws SQLException {
        UUID previous = rs.getObject("previous_version_id", UUID.class);
        return new ReportHeader(
                new TrendReportId(rs.getObject("id", UUID.class)),
                new ResearchRequestId(rs.getObject("research_request_id", UUID.class)),
                rs.getInt("version"),
                previous == null ? null : new TrendReportId(previous),
                new TechnologyDomainQuery(
                        rs.getString("raw_query"), rs.getString("normalized_query"), rs.getString("query_language")),
                new MethodologyRef(
                        rs.getString("methodology_version"),
                        rs.getObject("methodology_profile_id", UUID.class),
                        rs.getString("score_aggregator"),
                        rs.getString("engine"),
                        rs.getString("mode")),
                rs.getObject("corpus_snapshot_id", UUID.class),
                new Coverage(
                        rs.getInt("documents_analyzed"),
                        rs.getInt("candidates_evaluated"),
                        readArray(rs, "sources_used"),
                        readArray(rs, "unavailable_sources"),
                        rs.getBoolean("partial"),
                        rs.getBoolean("direction_recognized"),
                        readArray(rs, "direction_suggestions"),
                        rs.getInt("suppressed_by_analyst"),
                        rs.getBoolean("corpus_truncated"),
                        json.read(rs.getString("exclusions"), EXCLUSIONS),
                        readDate(rs, "window_from"),
                        readDate(rs, "window_to")),
                rs.getBoolean("truncated"),
                rs.getObject("generated_at", OffsetDateTime.class).toInstant());
    }

    private TrendReport withTrends(ReportHeader header) {
        var evidenceByRank = loadEvidence(header.id());
        var trends = jdbc.query(
                "SELECT * FROM report_trends WHERE report_id = :reportId ORDER BY rank",
                new MapSqlParameterSource("reportId", header.id().value()),
                (rs, rowNumber) -> mapTrend(rs, evidenceByRank));
        return TrendReport.rehydrate(
                header.id(),
                header.researchRequestId(),
                header.version(),
                header.previousVersionId(),
                header.query(),
                header.methodology(),
                header.corpusSnapshotId(),
                header.coverage(),
                header.truncated(),
                trends,
                header.generatedAt());
    }

    /** One row of {@code report_trend_evidence}, still carrying the rank it belongs to. */
    private record RankedEvidence(int rank, Evidence evidence) {}

    /**
     * Loads every evidence row of the report in one query and groups it in memory.
     *
     * <p>One query for all trends instead of one per trend: reading a report is the hot path
     * (NFR-P1) and an N+1 here would mean sixteen round trips where one suffices.
     */
    private Map<Integer, List<Evidence>> loadEvidence(TrendReportId reportId) {
        List<RankedEvidence> rows = jdbc.query(
                "SELECT * FROM report_trend_evidence WHERE report_id = :reportId ORDER BY rank, ordinal",
                new MapSqlParameterSource("reportId", reportId.value()),
                (rs, rowNumber) -> new RankedEvidence(
                        rs.getInt("rank"),
                        new Evidence(
                                rs.getString("source_id"),
                                SourceClass.valueOf(rs.getString("source_class")),
                                emptyToNull(rs.getString("external_id")),
                                rs.getString("title"),
                                rs.getString("authors"),
                                rs.getString("organization"),
                                rs.getString("organization_country"),
                                readDate(rs, "published_on"),
                                rs.getString("url"),
                                rs.getString("doi"),
                                readNullableInt(rs, "citation_count"),
                                rs.getDouble("relevance"),
                                rs.getString("snippet"),
                                rs.getString("language"),
                                readCredibility(rs.getString("credibility")),
                                rs.getString("credibility_basis"),
                                rs.getBoolean("independent"))));

        var byRank = new LinkedHashMap<Integer, List<Evidence>>();
        for (RankedEvidence row : rows) {
            byRank.computeIfAbsent(row.rank(), key -> new ArrayList<>()).add(row.evidence());
        }
        return byRank;
    }

    private RankedTrend mapTrend(ResultSet rs, Map<Integer, List<Evidence>> evidenceByRank) throws SQLException {
        int rank = rs.getInt("rank");
        var motivation = new Motivation(
                rs.getString("problem_statement"),
                rs.getString("benefit_statement"),
                json.read(rs.getString("motivation_attribution"), ATTRIBUTIONS));

        CaseExample caseExample = null;
        String caseOrganization = rs.getString("case_org_name");
        if (caseOrganization != null) {
            String type = rs.getString("case_org_type");
            caseExample = new CaseExample(
                    caseOrganization,
                    type == null ? null : CaseExample.OrganizationType.valueOf(type),
                    rs.getString("case_org_country"),
                    rs.getString("case_summary"),
                    rs.getInt("case_evidence_index"),
                    readCaseBasis(rs.getString("case_basis")));
        }

        String burstPeriod = rs.getString("burst_start_period");
        Burst burst = burstPeriod == null ? null : new Burst(burstPeriod, readNullableDouble(rs, "burst_weight"));

        return new RankedTrend(
                rank,
                rs.getString("trend_key"),
                rs.getString("title"),
                rs.getString("definition"),
                motivation,
                caseExample,
                new EmergenceAssessment(
                        rs.getDouble("emergence_score"),
                        rs.getDouble("confidence"),
                        rs.getBoolean("low_evidence"),
                        json.read(rs.getString("indicators"), INDICATORS),
                        readRankStability(rs)),
                LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
                rs.getInt("first_mention_year"),
                rs.getInt("total_documents"),
                burst,
                json.read(rs.getString("timeline"), TIMELINE),
                evidenceByRank.getOrDefault(rank, List.of()),
                readDirectionShare(rs),
                rs.getBoolean("low_credibility_only"),
                readLocalization(rs.getString("localization")),
                readExplanation(rs.getString("explanation")));
    }

    /**
     * Диапазон места или {@code null}, если он не измерялся.
     *
     * <p>{@code getInt} возвращает ноль и для {@code NULL}, поэтому проверка идёт через
     * {@code wasNull}: ноль здесь означал бы нулевое место, которого не бывает, и отчёт объявил бы
     * старые темы устойчивыми, не измерив ничего.
     */
    /** Доля направления или {@code null}: ноль здесь означал бы измеренный ноль, а не его отсутствие. */
    private static Double readDirectionShare(ResultSet rs) throws SQLException {
        double share = rs.getDouble("direction_share");
        return rs.wasNull() ? null : share;
    }

    /**
     * Уровень доверенности из строки.
     *
     * <p>Незнакомое значение читается как средний уровень, а не роняет чтение отчёта: колонка
     * ограничена CHECK-ом, но отчёт, выпущенный более новой версией движка, обязан открываться.
     */
    private static Credibility readCredibility(String value) {
        if (value == null || value.isBlank()) {
            return Credibility.MEDIUM;
        }
        try {
            return Credibility.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Credibility.MEDIUM;
        }
    }

    /** Русский слой из jsonb; пустой объект и {@code null} означают одно — моделей не было. */
    /** Объяснение темы или пустой список: у отчётов до 29.09 колонка содержит умолчание {@code []}. */
    private List<ExplanationItem> readExplanation(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return json.read(raw, EXPLANATION);
    }

    private TrendLocalization readLocalization(String raw) {
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return TrendLocalization.NONE;
        }
        var parsed = json.read(raw, LOCALIZATION);
        return parsed == null ? TrendLocalization.NONE : parsed;
    }

    private static RankStability readRankStability(ResultSet rs) throws SQLException {
        int best = rs.getInt("rank_stability_best");
        if (rs.wasNull()) {
            return null;
        }
        int worst = rs.getInt("rank_stability_worst");
        return rs.wasNull() ? null : new RankStability(best, worst);
    }

    // ── Small JDBC helpers ───────────────────────────────────────────────────────────────────

    private static List<String> readArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static LocalDate readDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Основание кейс-примера из строки отчёта.
     *
     * <p>{@code null} здесь — не порча данных, а отчёт, выпущенный до появления колонки: ограничение
     * таблицы допускает только известные значения, поэтому неизвестное сюда не доедет.
     */
    private static CaseExample.Basis readCaseBasis(String value) {
        return value == null ? null : CaseExample.Basis.valueOf(value);
    }
}
