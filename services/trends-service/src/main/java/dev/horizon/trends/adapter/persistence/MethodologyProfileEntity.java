package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code trends.methodology_profiles}.
 *
 * <p>{@code weights} and {@code parameters} are stored as raw JSON strings rather than as mapped
 * maps: they are opaque to SQL (never filtered or aggregated on), their shape is owned by the
 * methodology contract, and keeping them as text means a new indicator can be added without a
 * migration. {@link SqlTypes#JSON} makes the driver send them as {@code jsonb} instead of text, so
 * Postgres still validates the syntax.
 */
@Entity
@Table(name = "methodology_profiles")
public class MethodologyProfileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "methodology_version", nullable = false, length = 24)
    private String methodologyVersion;

    @Column(name = "aggregator", nullable = false, length = 32)
    private String aggregator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weights", nullable = false)
    private String weights;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false)
    private String parameters;

    @Column(name = "confidence_threshold", nullable = false)
    private double confidenceThreshold;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MethodologyProfileEntity() {
        // for JPA
    }

    @SuppressWarnings("java:S107") // Mirrors the row; a builder would add ceremony without safety.
    public MethodologyProfileEntity(
            UUID id,
            String name,
            int version,
            String methodologyVersion,
            String aggregator,
            String weights,
            String parameters,
            double confidenceThreshold,
            boolean isDefault,
            UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.methodologyVersion = methodologyVersion;
        this.aggregator = aggregator;
        this.weights = weights;
        this.parameters = parameters;
        this.confidenceThreshold = confidenceThreshold;
        this.isDefault = isDefault;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getMethodologyVersion() {
        return methodologyVersion;
    }

    public String getAggregator() {
        return aggregator;
    }

    public String getWeights() {
        return weights;
    }

    public String getParameters() {
        return parameters;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean value) {
        this.isDefault = value;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
