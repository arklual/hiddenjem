/**
 * «Отслеживать направление» — это сохранённое направление на сервере (`/saved-domains`).
 *
 * Сохраняется вопрос целиком — формулировка и параметры, — чтобы пакетный пересчёт радара задавал
 * тот же вопрос, а не похожий.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import { useSavedDomains } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import type { AnalysisParameters, SavedDomain } from '@/api/types';
import { useToast } from '@/components/toast/ToastContext';
import { directionTitle, normalizeQuery } from '@/features/report/topicModel';
import { toUiError } from '@/lib/errors';
import { useFeature } from '@/lib/features';

export function useTracking(): {
  enabled: boolean;
  saved: SavedDomain[] | undefined;
  loading: boolean;
  savedFor: (query: string) => SavedDomain | undefined;
  toggle: (query: string, parameters?: AnalysisParameters) => void;
  pending: boolean;
} {
  // Сохранённые направления выключаются реестром фич: тогда кнопок «Отслеживать» нет вовсе.
  const enabled = useFeature('saved-domains');
  const saved = useSavedDomains(enabled);
  const queryClient = useQueryClient();
  const toast = useToast();

  const savedFor = (query: string): SavedDomain | undefined => {
    const key = normalizeQuery(query);
    return saved.data?.find(
      (domain) => normalizeQuery(domain.normalizedQuery ?? domain.query) === key,
    );
  };

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.savedDomains });
  };

  const add = useMutation({
    mutationFn: (input: { query: string; parameters?: AnalysisParameters }) =>
      api.saveDomain({
        query: input.query,
        ...(input.parameters ? { parameters: input.parameters } : {}),
      }),
    onSuccess: (_, input) => {
      refresh();
      toast.show(`«${directionTitle(input.query)}» на радаре`, {
        description: 'Направление встанет первым среди отслеживаемых.',
      });
    },
    onError: (error) =>
      toast.show('Не удалось добавить на радар', {
        tone: 'critical',
        description: toUiError(error).message,
      }),
  });

  const remove = useMutation({
    mutationFn: (domain: SavedDomain) => api.deleteSavedDomain(domain.id),
    onSuccess: (_, domain) => {
      refresh();
      toast.show(`«${directionTitle(domain.query)}» убрано с радара`);
    },
    onError: (error) =>
      toast.show('Не удалось убрать с радара', {
        tone: 'critical',
        description: toUiError(error).message,
      }),
  });

  const toggle = (query: string, parameters?: AnalysisParameters): void => {
    const existing = savedFor(query);
    if (existing) remove.mutate(existing);
    else add.mutate({ query, ...(parameters ? { parameters } : {}) });
  };

  return {
    enabled,
    saved: saved.data,
    loading: saved.isLoading,
    savedFor,
    toggle,
    pending: add.isPending || remove.isPending,
  };
}
