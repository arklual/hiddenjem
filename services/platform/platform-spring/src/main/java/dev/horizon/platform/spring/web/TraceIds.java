package dev.horizon.platform.spring.web;

import org.slf4j.MDC;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Reads the current trace identity without forcing callers to depend on a tracing implementation.
 *
 * <p>Two different values, and the difference matters. {@link #current()} is the bare trace id — what
 * a log line carries so an operator can grep. {@link #currentTraceparent()} is the W3C
 * {@code traceparent} header, which additionally carries the parent span id and the sampling
 * decision, and is what another team's consumer parses.
 *
 * <p>They were the same method once, and the field published as {@code traceparent} in the message
 * envelope carried a bare trace id. The contract (`contracts/schemas/envelope.json`) describes that
 * field as "W3C Trace Context": a standards-aware consumer would fail to parse it, and the span it
 * created would not link to ours. The value looked right in logs, which is exactly why nobody
 * noticed.
 *
 * <p>Both degrade to {@code null} when tracing is off, rather than failing.
 */
public final class TraceIds {

    private TraceIds() {}

    /** Bare trace id from the MDC, e.g. {@code 4bf92f3577b34da6a3ce929d0e0e4736}. */
    public static String current() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    /**
     * W3C {@code traceparent} of the active span: {@code 00-<trace-id>-<span-id>-<flags>}.
     *
     * <p>Flags come from the real sampling decision rather than a constant: hard-coding {@code 01}
     * would tell every downstream consumer that the trace is sampled, and traces that were dropped
     * on our side would be recorded on theirs with no parent to link to.
     */
    public static String currentTraceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-"
                + context.getTraceFlags().asHex();
    }
}
