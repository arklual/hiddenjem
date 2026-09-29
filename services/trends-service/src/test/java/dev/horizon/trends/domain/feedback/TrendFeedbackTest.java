package dev.horizon.trends.domain.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.feedback.TrendFeedback.Verdict;
import dev.horizon.trends.domain.report.TrendReportId;

/**
 * Оценка тренда аналитиком (BR-E5, JTBD-7).
 *
 * <p>Это не украшение интерфейса, а размеченные данные: по ним считается Precision@15 и, позже,
 * учится ранжирование. Поэтому оценка публикуется доменным фактом, и её поля — часть контракта
 * сообщения, а не подробность реализации.
 *
 * <p>Мутационный прогон показал по этому классу 13%: проверялось почти ничего, хотя логика есть —
 * охранные условия, обрезка комментария и значения, по которым событие маршрутизируется.
 */
class TrendFeedbackTest {

    private static final UUID USER = UUID.randomUUID();
    private static final TrendReportId REPORT = TrendReportId.generate();
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Nested
    @DisplayName("Охранные условия")
    class Guards {

        @Test
        void пустойКлючТемыОтвергается() {
            assertThatThrownBy(() -> TrendFeedback.record(USER, REPORT, "", Verdict.RELEVANT, null, NOW))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void слишкомДлинныйКлючТемыОтвергается() {
            var tooLong = "t".repeat(161);

            assertThatThrownBy(() -> TrendFeedback.record(USER, REPORT, tooLong, Verdict.RELEVANT, null, NOW))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void граничнаяДлинаКлючаПринимается() {
            assertThatCode(() -> TrendFeedback.record(USER, REPORT, "t".repeat(160), Verdict.RELEVANT, null, NOW))
                    .doesNotThrowAnyException();
        }

        @Test
        void обязательныеПоляНеМогутОтсутствовать() {
            assertThatThrownBy(() -> TrendFeedback.record(null, REPORT, "topic", Verdict.RELEVANT, null, NOW))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> TrendFeedback.record(USER, null, "topic", Verdict.RELEVANT, null, NOW))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> TrendFeedback.record(USER, REPORT, "topic", null, null, NOW))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> TrendFeedback.record(USER, REPORT, "topic", Verdict.RELEVANT, null, null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Комментарий")
    class Comment {

        @Test
        void длинныйКомментарийОбрезаетсяДоДвухТысяч() {
            // Обрезка, а не отказ: аналитик написал развёрнуто — это его труд, терять его целиком
            // из-за длины нельзя. Но и хранить без предела нечего.
            var feedback = TrendFeedback.record(USER, REPORT, "topic", Verdict.NOISE, "c".repeat(2500), NOW);

            assertThat(feedback.comment()).hasSize(2000);
        }

        @Test
        void граничнаяДлинаНеТрогается() {
            var exact = "c".repeat(2000);

            assertThat(TrendFeedback.record(USER, REPORT, "topic", Verdict.NOISE, exact, NOW)
                            .comment())
                    .isEqualTo(exact);
        }

        @Test
        void короткийКомментарийСохраняетсяДословно() {
            assertThat(TrendFeedback.record(USER, REPORT, "topic", Verdict.NOISE, "мимо", NOW)
                            .comment())
                    .isEqualTo("мимо");
        }

        @Test
        void комментарийНеобязателен() {
            assertThat(TrendFeedback.record(USER, REPORT, "topic", Verdict.ALREADY_KNOWN, null, NOW)
                            .comment())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Событие")
    class AsEvent {

        private final TrendFeedback feedback =
                TrendFeedback.record(USER, REPORT, "topic-1", Verdict.RELEVANT, "в точку", NOW);

        @Test
        void опознаётсяПоТипуИагрегату() {
            // Эти строки — часть контракта: по ним сообщение разбирается на другой стороне и
            // раскладывается в outbox. Молчаливая замена любой из них ломает разбор у потребителя.
            assertThat(feedback.eventType()).isEqualTo("horizon.trends.TrendFeedbackRecorded");
            assertThat(feedback.aggregateType()).isEqualTo("TrendFeedback");
            assertThat(feedback.aggregateId()).isEqualTo(feedback.id().toString());
        }

        @Test
        void идентификаторИвремяСобытияБерутсяИзСамойОценки() {
            assertThat(feedback.eventId()).isEqualTo(feedback.id());
            assertThat(feedback.occurredAt()).isEqualTo(NOW);
        }

        @Test
        void ключРазделаЭтоОтчёт() {
            // Оценки одного отчёта обязаны попадать в один раздел: иначе их порядок между собой не
            // гарантирован, а считаются они вместе.
            assertThat(feedback.partitionKey()).isEqualTo(REPORT.toString());
        }

        @Test
        void полезнаяНагрузкаНесётВсё() {
            var payload = (TrendFeedback.Payload) feedback.payload();

            assertThat(payload.reportId()).isEqualTo(REPORT.toString());
            assertThat(payload.trendKey()).isEqualTo("topic-1");
            assertThat(payload.userId()).isEqualTo(USER.toString());
            assertThat(payload.verdict()).isEqualTo("RELEVANT");
            assertThat(payload.comment()).isEqualTo("в точку");
            assertThat(payload.recordedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void идентификаторыРазныеУразныхОценок() {
        var first = TrendFeedback.record(USER, REPORT, "topic", Verdict.RELEVANT, null, NOW);
        var second = TrendFeedback.record(USER, REPORT, "topic", Verdict.RELEVANT, null, NOW);

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
