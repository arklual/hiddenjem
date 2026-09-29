package dev.horizon.trends.adapter.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Проекция {@code report_trends} для чтения (BR-A67).
 *
 * <p>Отчёт пишется {@link JdbcTrendReportRepository}: там {@code jsonb}, массивы и пакетная
 * вставка. Здесь нужно обратное — соединить три таблицы по ключам и отобрать по подстроке, и это
 * ровно то, что JPQL выражает, а H2 исполняет. Рукописный SQL был бы короче и не проверялся бы
 * ничем: он целиком назван непокрытым в спецификации среднего слоя.
 *
 * <p>{@link Immutable}: половина колонок таблицы сюда не отображена, и запись через эту проекцию
 * означала бы их потерю. Пусть попытка не компилируется, а не портит отчёт.
 */
@Entity
@Immutable
@Table(name = "report_trends")
public class ReportTrendRow {

    /** Первичный ключ таблицы: место темы уникально внутри отчёта. */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "report_id", nullable = false)
        private UUID reportId;

        @Column(name = "rank", nullable = false)
        private short rank;

        protected Key() {}

        public UUID getReportId() {
            return reportId;
        }

        public short getRank() {
            return rank;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return rank == key.rank && Objects.equals(reportId, key.reportId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reportId, rank);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "trend_key", nullable = false, length = 160)
    private String trendKey;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * Отображены ради поиска, а не показа: название темы — это извлечённый из корпуса термин, и
     * искомое слово чаще стоит в определении или в формулировке проблемы, чем в заголовке.
     */
    @Column(name = "definition", nullable = false)
    private String definition;

    @Column(name = "problem_statement", nullable = false)
    private String problemStatement;

    protected ReportTrendRow() {}

    public Key getId() {
        return id;
    }

    public String getTrendKey() {
        return trendKey;
    }

    public String getTitle() {
        return title;
    }

    public String getDefinition() {
        return definition;
    }

    public String getProblemStatement() {
        return problemStatement;
    }
}
