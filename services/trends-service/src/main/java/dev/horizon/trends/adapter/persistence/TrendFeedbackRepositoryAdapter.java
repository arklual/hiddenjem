package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;

/** Implements {@link TrendFeedbackRepository} on top of JPA. */
@Repository
public class TrendFeedbackRepositoryAdapter implements TrendFeedbackRepository {

    private final TrendFeedbackJpaRepository jpa;

    public TrendFeedbackRepositoryAdapter(TrendFeedbackJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrendFeedback> find(UUID userId, TrendReportId reportId, String trendKey) {
        return jpa.findByUserIdAndReportIdAndTrendKey(userId, reportId.value(), trendKey)
                .map(TrendFeedbackRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrendFeedback> findByUserAndReport(UUID userId, TrendReportId reportId) {
        return jpa.findByUserIdAndReportId(userId, reportId.value()).stream()
                .map(TrendFeedbackRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrendFeedback> findCarried(UUID userId, String normalizedQuery, TrendReportId excludingReportId) {
        return jpa.findCarried(userId, normalizedQuery, excludingReportId.value()).stream()
                .map(TrendFeedbackRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteByDirection(UUID userId, String normalizedQuery, String trendKey) {
        return jpa.deleteByDirection(userId, normalizedQuery, trendKey);
    }

    @Override
    public List<TrendFeedback> findByDirection(UUID userId, String normalizedQuery) {
        return jpa.findByDirection(userId, normalizedQuery).stream()
                .map(TrendFeedbackRepositoryAdapter::toDomain)
                .toList();
    }

    /**
     * Upsert semantics, matching the contract's {@code PUT}.
     *
     * <p>The API models a verdict as a property of (user, trend), not as an append-only log, so a
     * second rating revises the existing row rather than inserting a duplicate that would violate
     * {@code ux_feedback_user_trend}. The returned aggregate keeps the caller's identifier because it
     * is also the domain event that will be published.
     */
    @Override
    public TrendFeedback save(TrendFeedback feedback) {
        var existing = jpa.findByUserIdAndReportIdAndTrendKey(
                        feedback.userId(), feedback.reportId().value(), feedback.trendKey())
                .orElse(null);
        if (existing != null) {
            existing.revise(feedback.verdict().name(), feedback.comment(), feedback.createdAt());
            return feedback;
        }
        jpa.save(new TrendFeedbackEntity(
                feedback.id(),
                feedback.userId(),
                feedback.reportId().value(),
                feedback.trendKey(),
                feedback.verdict().name(),
                feedback.comment(),
                feedback.createdAt()));
        return feedback;
    }

    private static TrendFeedback toDomain(TrendFeedbackEntity entity) {
        return new TrendFeedback(
                entity.getId(),
                entity.getUserId(),
                new TrendReportId(entity.getReportId()),
                entity.getTrendKey(),
                TrendFeedback.Verdict.valueOf(entity.getVerdict()),
                entity.getComment(),
                entity.getCreatedAt());
    }
}
