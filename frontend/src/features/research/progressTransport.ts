/**
 * Progress transport with automatic degradation (ADR-0012).
 *
 * SSE is the primary transport. The client falls back to polling
 * `GET /research-requests/{id}` when either of the two conditions in the ADR
 * happens:
 *
 *  1. the stream cannot be established, or dies (connection error / non-2xx);
 *  2. nothing arrives for longer than the heartbeat interval — the server sends
 *     a `heartbeat` every 15 s specifically so silence is diagnosable, and a
 *     proxy that buffers the stream produces exactly this symptom.
 *
 * The fallback is one-way on purpose: once degraded, the run finishes on
 * polling. Flapping between transports mid-analysis would produce a confusing,
 * un-debuggable progress display for no benefit over a 90-second job.
 *
 * All I/O is injected, so the state machine is testable without a server and
 * without anything resembling a fake backend.
 */
import type { ResearchRequestView } from '@/api/types';
import { isTerminalStatus } from '@/api/types';

export type TransportMode = 'sse' | 'polling';

export interface TransportEvent {
  event: string;
  data: string;
  id: string | null;
}

export interface OpenStreamOptions {
  signal: AbortSignal;
  lastEventId: string | null;
  onOpen: () => void;
  onEvent: (event: TransportEvent) => void;
}

export interface ProgressTransportDeps {
  /** Opens the SSE stream; resolves on clean end-of-stream, rejects on error. */
  openStream: (options: OpenStreamOptions) => Promise<void>;
  /** One-shot status read, used by the polling fallback. */
  poll: (signal: AbortSignal) => Promise<ResearchRequestView>;
  /** Parses an SSE `data:` payload; returns null when it does not validate. */
  parse: (data: string) => ResearchRequestView | null;

  onUpdate: (view: ResearchRequestView) => void;
  onModeChange?: (mode: TransportMode, reason: DegradeReason | null) => void;
  /** Called when progress can no longer be tracked at all. */
  onError?: (error: unknown) => void;

  /** Polling cadence. ADR-0012 sizes this at 2 s. */
  pollIntervalMs?: number;
  /** Silence tolerated on the stream. Must exceed the 15 s heartbeat. */
  staleTimeoutMs?: number;
  /** Consecutive poll failures tolerated before giving up. */
  maxPollFailures?: number;
}

export type DegradeReason = 'stream-error' | 'stream-stalled' | 'stream-closed';

export interface ProgressTransport {
  mode: () => TransportMode;
  stop: () => void;
}

export const DEFAULT_POLL_INTERVAL_MS = 2000;
/** 15 s heartbeat + margin for a slow hop. */
export const DEFAULT_STALE_TIMEOUT_MS = 40000;
const DEFAULT_MAX_POLL_FAILURES = 3;

export function startProgressTransport(deps: ProgressTransportDeps): ProgressTransport {
  const pollIntervalMs = deps.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const staleTimeoutMs = deps.staleTimeoutMs ?? DEFAULT_STALE_TIMEOUT_MS;
  const maxPollFailures = deps.maxPollFailures ?? DEFAULT_MAX_POLL_FAILURES;

  let mode: TransportMode = 'sse';
  let stopped = false;
  let lastEventId: string | null = null;
  let consecutivePollFailures = 0;

  const streamAbort = new AbortController();
  const pollAbort = new AbortController();
  let staleTimer: ReturnType<typeof setTimeout> | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;

  function clearStaleTimer(): void {
    if (staleTimer !== null) {
      clearTimeout(staleTimer);
      staleTimer = null;
    }
  }

  function clearPollTimer(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  function stop(): void {
    if (stopped) return;
    stopped = true;
    clearStaleTimer();
    clearPollTimer();
    streamAbort.abort();
    pollAbort.abort();
  }

  /** Publishes an update and stops everything once the request is terminal. */
  function publish(view: ResearchRequestView): void {
    if (stopped) return;
    deps.onUpdate(view);
    if (isTerminalStatus(view.status)) stop();
  }

  function armStaleTimer(): void {
    clearStaleTimer();
    if (stopped || mode !== 'sse') return;
    staleTimer = setTimeout(() => {
      degrade('stream-stalled');
    }, staleTimeoutMs);
  }

  function degrade(reason: DegradeReason): void {
    if (stopped || mode === 'polling') return;
    mode = 'polling';
    clearStaleTimer();
    streamAbort.abort();
    deps.onModeChange?.('polling', reason);
    void runPollCycle();
  }

  async function runPollCycle(): Promise<void> {
    if (stopped) return;
    try {
      const view = await deps.poll(pollAbort.signal);
      consecutivePollFailures = 0;
      publish(view);
    } catch (error) {
      if (stopped) return;
      // An abort is our own doing, never a failure to report.
      if (error instanceof DOMException && error.name === 'AbortError') return;
      consecutivePollFailures += 1;
      if (consecutivePollFailures >= maxPollFailures) {
        deps.onError?.(error);
        stop();
        return;
      }
    }
    if (stopped) return;
    clearPollTimer();
    pollTimer = setTimeout(() => void runPollCycle(), pollIntervalMs);
  }

  function handleStreamEvent(event: TransportEvent): void {
    if (stopped) return;
    if (event.id !== null) lastEventId = event.id;
    // Any frame — including a heartbeat carrying no payload — proves the stream
    // is alive, so the silence timer restarts on all of them.
    armStaleTimer();

    if (event.event === 'heartbeat') return;

    const view = deps.parse(event.data);
    if (view) publish(view);
  }

  function startStream(): void {
    armStaleTimer();
    deps
      .openStream({
        signal: streamAbort.signal,
        lastEventId,
        onOpen: () => armStaleTimer(),
        onEvent: handleStreamEvent,
      })
      .then(() => {
        // Clean end-of-stream. If the request is not finished yet, the server
        // closed early (a proxy idle timeout, a rolling deploy) — keep tracking
        // by polling rather than silently freezing the progress bar.
        if (!stopped) degrade('stream-closed');
      })
      .catch((error: unknown) => {
        if (stopped) return;
        if (error instanceof DOMException && error.name === 'AbortError') return;
        degrade('stream-error');
      });
  }

  deps.onModeChange?.('sse', null);
  startStream();

  return {
    mode: () => mode,
    stop,
  };
}
