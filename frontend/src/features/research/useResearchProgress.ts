/**
 * Binds the progress transport to the real API and to React state.
 *
 * The stream and the polling fallback are both validated by the *same* contract
 * schema (`researchRequestViewSchema`), so the two transports cannot disagree
 * about the shape of what they deliver.
 */
import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, eventStreamUrl, parseEventPayload } from '@/api/client';
import { researchRequestViewSchema } from '@/api/schemas';
import { streamSse } from '@/api/sse';
import { queryKeys } from '@/api/queryKeys';
import type { ResearchRequestView } from '@/api/types';
import { isTerminalStatus } from '@/api/types';
import {
  startProgressTransport,
  type DegradeReason,
  type ProgressTransport,
  type TransportMode,
} from './progressTransport';

function readEnvNumber(raw: string | undefined, fallback: number): number {
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export interface ResearchProgressState {
  view: ResearchRequestView | null;
  mode: TransportMode;
  degradeReason: DegradeReason | null;
  error: unknown;
  /** Seconds since the hook started tracking, for the elapsed-time display. */
  elapsedSeconds: number;
}

export function useResearchProgress(
  requestId: string | undefined,
  initialView?: ResearchRequestView,
): ResearchProgressState {
  const queryClient = useQueryClient();
  const [view, setView] = useState<ResearchRequestView | null>(initialView ?? null);
  const [mode, setMode] = useState<TransportMode>('sse');
  const [degradeReason, setDegradeReason] = useState<DegradeReason | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const transportRef = useRef<ProgressTransport | null>(null);

  // Elapsed timer: independent of the transport so the clock keeps running
  // through a fallback switch.
  useEffect(() => {
    if (!requestId) return;
    const startedAt = Date.now();
    setElapsedSeconds(0);
    const timer = setInterval(() => {
      setElapsedSeconds(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => clearInterval(timer);
  }, [requestId]);

  useEffect(() => {
    if (!requestId) return;
    // A request that is already finished needs no transport at all.
    if (initialView && isTerminalStatus(initialView.status)) return;

    setError(null);

    const transport = startProgressTransport({
      openStream: async ({ signal, lastEventId, onOpen, onEvent }) => {
        await streamSse({
          url: eventStreamUrl(requestId),
          lastEventId,
          signal,
          onOpen,
          onEvent: (event) => onEvent({ event: event.event, data: event.data, id: event.id }),
        });
      },
      poll: (signal) => api.getResearchRequest(requestId, signal),
      parse: (data) => parseEventPayload(data, researchRequestViewSchema),
      onUpdate: (next) => {
        setView(next);
        // Keep the query cache in step so other screens (history, report) read
        // the same state without a second request.
        queryClient.setQueryData(queryKeys.researchRequest(requestId), next);
      },
      onModeChange: (nextMode, reason) => {
        setMode(nextMode);
        setDegradeReason(reason);
      },
      onError: (transportError) => setError(transportError),
      pollIntervalMs: readEnvNumber(import.meta.env.VITE_PROGRESS_POLL_INTERVAL_MS, 2000),
      staleTimeoutMs: readEnvNumber(import.meta.env.VITE_SSE_STALE_TIMEOUT_MS, 40000),
    });

    transportRef.current = transport;
    return () => {
      transport.stop();
      transportRef.current = null;
    };
    // `initialView` is intentionally not a dependency: it only seeds the first
    // render, and re-subscribing on every parent re-render would restart the
    // stream mid-analysis.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestId, queryClient]);

  return { view, mode, degradeReason, error, elapsedSeconds };
}
