package dev.horizon.platform.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

/**
 * The message envelope publishes a field named {@code traceparent}, and
 * {@code contracts/schemas/envelope.json} describes it as "W3C Trace Context". It carried a bare
 * trace id: a consumer built from the contract would fail to parse it, and the span it created
 * would not link to ours. In logs the value looked right, which is why it survived.
 */
class TraceIdsTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    @DisplayName("traceparent имеет формат W3C, а не голый идентификатор")
    void theTraceparentIsAWireFormatHeader() {
        SpanContext context = SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            assertThat(TraceIds.currentTraceparent()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        }
    }

    @Test
    @DisplayName("флаг выборки берётся из решения, а не проставляется константой")
    void theSamplingFlagIsTheRealOne() {
        // Константа `01` сообщала бы каждому потребителю, что трасса записана; трассы, отброшенные
        // у нас, оказались бы записанными у него — без родителя, к которому можно привязаться.
        SpanContext context = SpanContext.create(TRACE, SPAN, TraceFlags.getDefault(), TraceState.getDefault());
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            assertThat(TraceIds.currentTraceparent()).endsWith("-00");
        }
    }

    @Test
    @DisplayName("без активной трассы возвращается null, а не выдуманный заголовок")
    void withoutATraceThereIsNoHeader() {
        assertThat(TraceIds.currentTraceparent()).isNull();
    }

    @Test
    @DisplayName("голый идентификатор для логов читается из MDC и остаётся прежним")
    void theBareTraceIdStillComesFromTheMdc() {
        MDC.put("traceId", TRACE);
        try {
            assertThat(TraceIds.current()).isEqualTo(TRACE);
        } finally {
            MDC.remove("traceId");
        }
    }
}
