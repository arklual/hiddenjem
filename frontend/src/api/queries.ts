/**
 * Запросы, которые делят между собой несколько экранов Hiddenjem.
 *
 * Радар, новый анализ, отчёты и поиск читают один и тот же список запусков: общий ключ кэша
 * значит один поход к серверу на все четыре экрана и одинаковый ответ на каждом.
 */
import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import { queryKeys } from './queryKeys';
import type { ResearchRequestView } from './types';
import { normalizeQuery } from '@/features/report/topicModel';

/** Сколько последних запусков читается для радара и подсказок — хватает на все направления. */
export const RECENT_REQUESTS = 50;

export function useRecentRequests(onlyMine = false, size = RECENT_REQUESTS) {
  return useQuery({
    queryKey: queryKeys.researchRequests(0, size, undefined, onlyMine),
    queryFn: ({ signal }) => api.listResearchRequests({ page: 0, size, onlyMine }, signal),
    // Идущий анализ меняет список сам: без обновления радар показывал бы «идёт» у готового.
    refetchInterval: (query) =>
      query.state.data?.content.some((request) => !isFinished(request)) ? 15_000 : false,
  });
}

export function useReport(reportId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.report(reportId ?? ''),
    queryFn: ({ signal }) => api.getReport(reportId ?? '', signal),
    enabled: Boolean(reportId),
    // Отчёт неизменяем: однажды прочитанный, он не устаревает.
    staleTime: Infinity,
  });
}

export function useReportDelta(reportId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.reportDelta(reportId ?? ''),
    queryFn: ({ signal }) => api.getReportDelta(reportId ?? '', signal),
    enabled: Boolean(reportId) && enabled,
    staleTime: Infinity,
  });
}

export function useSavedDomains(enabled = true) {
  return useQuery({
    queryKey: queryKeys.savedDomains,
    queryFn: ({ signal }) => api.listSavedDomains(signal),
    enabled,
  });
}

export function useQuota() {
  return useQuery({
    queryKey: queryKeys.quota,
    queryFn: ({ signal }) => api.getQuota(signal),
  });
}

export function useKnownDirections() {
  return useQuery({
    queryKey: queryKeys.knownDirections,
    queryFn: ({ signal }) => api.knownDirections(signal),
    staleTime: 10 * 60_000,
  });
}

function isFinished(request: ResearchRequestView): boolean {
  return (
    request.status === 'COMPLETED' || request.status === 'FAILED' || request.status === 'CANCELLED'
  );
}

export function isRunning(request: ResearchRequestView): boolean {
  return !isFinished(request);
}

/** Направление запуска — ключ, по которому сводятся повторные прогоны одного вопроса. */
export function directionKey(request: {
  query: string;
  normalizedQuery?: string | undefined;
}): string {
  return normalizeQuery(request.normalizedQuery ?? request.query);
}

/**
 * Последний готовый отчёт по каждому направлению — от свежего к старому.
 *
 * Список запусков уже отсортирован сервером от новых к старым; первый готовый по направлению и есть
 * его актуальный отчёт.
 */
export function latestReports(requests: readonly ResearchRequestView[]): ResearchRequestView[] {
  const seen = new Set<string>();
  const result: ResearchRequestView[] = [];
  for (const request of requests) {
    if (request.status !== 'COMPLETED' || !request.reportId) continue;
    const key = directionKey(request);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(request);
  }
  return result;
}

/** Готовый отчёт по формулировке — чтобы открыть его вместо нового запуска. */
export function readyReportFor(
  requests: readonly ResearchRequestView[] | undefined,
  query: string,
): ResearchRequestView | undefined {
  const key = normalizeQuery(query);
  if (!key) return undefined;
  return requests?.find(
    (request) =>
      request.status === 'COMPLETED' && request.reportId && directionKey(request) === key,
  );
}
