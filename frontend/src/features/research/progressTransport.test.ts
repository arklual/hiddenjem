import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ResearchRequestView } from '@/api/types';
import {
  startProgressTransport,
  type OpenStreamOptions,
  type ProgressTransportDeps,
  type TransportMode,
} from './progressTransport';

function view(overrides: Partial<ResearchRequestView> = {}): ResearchRequestView {
  return {
    id: '00000000-0000-7000-8000-000000000001',
    query: 'технологии в ИИ',
    parameters: { topN: 15, yearsWindow: 7 },
    status: 'ANALYZING',
    progress: { stage: 'ANALYZING', percent: 55 },
    submittedAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

interface Harness {
  deps: ProgressTransportDeps;
  updates: ResearchRequestView[];
  modes: Array<{ mode: TransportMode; reason: string | null }>;
  errors: unknown[];
  /** Resolves once the transport has subscribed to the stream. */
  stream: () => OpenStreamOptions;
  failStream: (error: unknown) => void;
  endStream: () => void;
  pollFn: ReturnType<typeof vi.fn>;
}

function harness(overrides: Partial<ProgressTransportDeps> = {}): Harness {
  let options: OpenStreamOptions | null = null;
  let reject!: (error: unknown) => void;
  let resolve!: () => void;

  const updates: ResearchRequestView[] = [];
  const modes: Array<{ mode: TransportMode; reason: string | null }> = [];
  const errors: unknown[] = [];
  const pollFn = vi.fn(() => Promise.resolve(view()));

  const deps: ProgressTransportDeps = {
    openStream: (opts) =>
      new Promise<void>((res, rej) => {
        options = opts;
        resolve = res;
        reject = rej;
      }),
    poll: pollFn,
    parse: (data) => JSON.parse(data) as ResearchRequestView,
    onUpdate: (value) => updates.push(value),
    onModeChange: (mode, reason) => modes.push({ mode, reason }),
    onError: (error) => errors.push(error),
    pollIntervalMs: 1000,
    staleTimeoutMs: 5000,
    maxPollFailures: 2,
    ...overrides,
  };

  return {
    deps,
    updates,
    modes,
    errors,
    stream: () => {
      if (!options) throw new Error('stream was never opened');
      return options;
    },
    failStream: (error) => reject(error),
    endStream: () => resolve(),
    pollFn,
  };
}

describe('progressTransport', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts on SSE and publishes parsed events', async () => {
    const h = harness();
    const transport = startProgressTransport(h.deps);

    h.stream().onOpen();
    h.stream().onEvent({ event: 'progress', data: JSON.stringify(view()), id: '1' });
    await vi.advanceTimersByTimeAsync(0);

    expect(transport.mode()).toBe('sse');
    expect(h.updates).toHaveLength(1);
    expect(h.updates[0]?.progress.percent).toBe(55);
    expect(h.pollFn).not.toHaveBeenCalled();
    transport.stop();
  });

  it('degrades to polling when the stream errors', async () => {
    // The behaviour ADR-0012 promises: a client behind a proxy that forbids event streams
    // must still see progress, without any mocking or feature detection.
    const h = harness();
    const transport = startProgressTransport(h.deps);

    h.failStream(new Error('stream refused'));
    await vi.advanceTimersByTimeAsync(0);

    expect(transport.mode()).toBe('polling');
    expect(h.modes.at(-1)).toEqual({ mode: 'polling', reason: 'stream-error' });

    await vi.advanceTimersByTimeAsync(1000);
    expect(h.pollFn).toHaveBeenCalled();
    transport.stop();
  });

  it('degrades to polling when the stream goes silent past the heartbeat window', async () => {
    const h = harness();
    const transport = startProgressTransport(h.deps);
    h.stream().onOpen();

    await vi.advanceTimersByTimeAsync(5001);

    expect(transport.mode()).toBe('polling');
    expect(h.modes.at(-1)?.reason).toBe('stream-stalled');
    transport.stop();
  });

  it('keeps the stream alive while events keep arriving', async () => {
    const h = harness();
    const transport = startProgressTransport(h.deps);
    h.stream().onOpen();

    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(4000);
      // Без полезной нагрузки — ровно так, как heartbeat приходит с сервера.
      h.stream().onEvent({ event: 'heartbeat', data: '', id: String(i) });
    }

    expect(transport.mode()).toBe('sse');
    transport.stop();
  });

  it('stops once the request reaches a terminal state', async () => {
    const h = harness();
    const transport = startProgressTransport(h.deps);
    h.stream().onOpen();

    h.stream().onEvent({
      event: 'completed',
      data: JSON.stringify(view({ status: 'COMPLETED', reportId: 'r-1' })),
      id: '9',
    });
    await vi.advanceTimersByTimeAsync(10_000);

    expect(h.updates.at(-1)?.status).toBe('COMPLETED');
    // Nothing may keep running after a terminal state — a leaked poller would hammer the
    // API for as long as the tab stays open.
    expect(h.pollFn).not.toHaveBeenCalled();
    transport.stop();
  });

  it('gives up after the configured number of consecutive poll failures', async () => {
    const h = harness({ poll: vi.fn(() => Promise.reject(new Error('gateway down'))) });
    const transport = startProgressTransport(h.deps);

    h.failStream(new Error('stream refused'));
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1000);
    await vi.advanceTimersByTimeAsync(1000);
    await vi.advanceTimersByTimeAsync(1000);

    expect(h.errors.length).toBeGreaterThan(0);
    transport.stop();
  });

  it('ignores unparsable payloads instead of tearing the stream down', async () => {
    const h = harness({ parse: () => null });
    const transport = startProgressTransport(h.deps);
    h.stream().onOpen();

    h.stream().onEvent({ event: 'progress', data: 'not json', id: '1' });
    await vi.advanceTimersByTimeAsync(0);

    expect(h.updates).toHaveLength(0);
    expect(transport.mode()).toBe('sse');
    transport.stop();
  });

  it('stop() is idempotent and silences everything', async () => {
    const h = harness();
    const transport = startProgressTransport(h.deps);
    h.stream().onOpen();

    transport.stop();
    transport.stop();
    await vi.advanceTimersByTimeAsync(20_000);

    expect(h.pollFn).not.toHaveBeenCalled();
  });
});
