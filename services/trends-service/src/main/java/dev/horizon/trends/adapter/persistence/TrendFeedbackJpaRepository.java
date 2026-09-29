package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendFeedbackJpaRepository extends JpaRepository<TrendFeedbackEntity, UUID> {

    Optional<TrendFeedbackEntity> findByUserIdAndReportIdAndTrendKey(UUID userId, UUID reportId, String trendKey);

    List<TrendFeedbackEntity> findByUserIdAndReportId(UUID userId, UUID reportId);

    /**
     * Last verdict per topic across the other versions of the same direction.
     *
     * <p>Native because {@code trend_reports} has no JPA entity — it is written and read through
     * {@code JdbcTrendReportRepository} — so there is no association to join along. {@code DISTINCT
     * ON} does the "latest per key" in one pass; the alternative, reading every verdict the analyst
     * ever gave on this direction and reducing in Java, grows with their history rather than with
     * the report.
     */
    @Query(
            value =
                    """
                    SELECT DISTINCT ON (f.trend_key) f.*
                    FROM trend_feedback f
                    JOIN trend_reports r ON r.id = f.report_id
                    WHERE f.user_id = :userId
                      AND r.normalized_query = :normalizedQuery
                      AND f.report_id <> :excludingReportId
                    ORDER BY f.trend_key, f.created_at DESC, f.id DESC
                    """,
            nativeQuery = true)
    List<TrendFeedbackEntity> findCarried(
            @Param("userId") UUID userId,
            @Param("normalizedQuery") String normalizedQuery,
            @Param("excludingReportId") UUID excludingReportId);

    /**
     * The same "latest verdict per topic of this direction", but without excluding a report.
     *
     * <p>Needed when the command is built: there is no report yet to exclude. Reusing
     * {@code findCarried} with a sentinel identifier would work and would also encode "no report"
     * as a magic UUID that the next reader has to decode.
     */
    @Query(
            value =
                    """
                    SELECT DISTINCT ON (f.trend_key) f.*
                    FROM trend_feedback f
                    JOIN trend_reports r ON r.id = f.report_id
                    WHERE f.user_id = :userId
                      AND r.normalized_query = :normalizedQuery
                    ORDER BY f.trend_key, f.created_at DESC, f.id DESC
                    """,
            nativeQuery = true)
    List<TrendFeedbackEntity> findByDirection(
            @Param("userId") UUID userId, @Param("normalizedQuery") String normalizedQuery);

    /** Снять пометки аналитика с темы этого направления — во всех версиях отчёта. */
    @Modifying
    @Query(
            value =
                    """
                    DELETE FROM trend_feedback f
                    USING trend_reports r
                    WHERE r.id = f.report_id
                      AND f.user_id = :userId
                      AND r.normalized_query = :normalizedQuery
                      AND f.trend_key = :trendKey
                    """,
            nativeQuery = true)
    int deleteByDirection(
            @Param("userId") UUID userId,
            @Param("normalizedQuery") String normalizedQuery,
            @Param("trendKey") String trendKey);
}
