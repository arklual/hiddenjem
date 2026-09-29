/**
 * «Можно не ждать»: следит за анализами, запущенными из этого браузера, и сообщает, когда отчёт
 * готов, — на любом экране.
 *
 * Опрос редкий (раз в 10 секунд) и только по своим запускам: экран хода анализа держит собственный
 * поток событий, а здесь нужен лишь момент завершения. Пока аналитик смотрит на сам ход анализа,
 * уведомление не показывается — экран и так говорит, что всё готово.
 */
import { useEffect } from 'react';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import { isTerminalStatus } from '@/api/types';
import { useToast } from '@/components/toast/ToastContext';
import { directionTitle } from '@/features/report/topicModel';
import { explainFailure } from '@/features/research/failure';
import { RUNS_KEY, readLocal, useLocal, writeLocal, type TrackedRun } from '@/lib/localState';

const POLL_MS = 10_000;

export function RunWatcher(): null {
  const [runs] = useLocal<TrackedRun[]>(RUNS_KEY, []);
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();

  const results = useQueries({
    queries: runs.map((run) => ({
      queryKey: queryKeys.researchRequest(run.requestId),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api.getResearchRequest(run.requestId, signal),
      refetchInterval: (query: {
        state: { data?: { status: Parameters<typeof isTerminalStatus>[0] } };
      }) => (query.state.data && isTerminalStatus(query.state.data.status) ? false : POLL_MS),
    })),
  });

  useEffect(() => {
    results.forEach((result, index) => {
      const run = runs[index];
      const view = result.data;
      if (!run || !view || !isTerminalStatus(view.status)) return;
      writeLocal(
        RUNS_KEY,
        readLocal<TrackedRun[]>(RUNS_KEY, []).filter((item) => item.requestId !== run.requestId),
      );
      void queryClient.invalidateQueries({ queryKey: ['research-requests'] });
      if (location.pathname === `/runs/${run.requestId}`) return;
      const name = directionTitle(run.query);
      if (view.status === 'COMPLETED' && view.reportId) {
        const reportId = view.reportId;
        toast.show(`Отчёт «${name}» готов`, {
          tone: 'good',
          ...(view.partial
            ? { description: 'Часть источников не ответила — отчёт неполный.' }
            : {}),
          action: { label: 'Открыть', onClick: () => navigate(`/reports/${reportId}`) },
        });
      } else if (view.status === 'FAILED') {
        toast.show(`Анализ «${name}» не выполнен`, {
          tone: 'critical',
          description: explainFailure(view.failure?.code).title,
          action: { label: 'Подробнее', onClick: () => navigate(`/runs/${run.requestId}`) },
        });
      }
    });
  }, [results, runs, location.pathname, toast, navigate, queryClient]);

  return null;
}
