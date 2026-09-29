/**
 * Source status and manual ingestion runs — open to everyone: there is no sign-in.
 *
 * A source that requires an API key but has none configured is called out
 * explicitly — that is the single most common reason a run comes back `partial`
 * (BRULE-8), and it is invisible otherwise.
 */
import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import type { SourceView } from '@/api/types';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Card, CardBody, CardHeader } from '@/components/Card';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PageHeader, PageStack } from '@/components/PageHeader';
import { SkeletonList } from '@/components/Skeleton';
import { Pagination, Table, Td, Th } from '@/components/Table';
import { RunStatusBadge, SourceClassBadge } from '@/components/domain/DomainBadges';
import { useToast } from '@/components/toast/ToastContext';
import { toUiError } from '@/lib/errors';
import { formatCount, formatDateTime, formatRelativeTime, toDateTimeAttr } from '@/lib/format';
import { useT } from '@/lib/i18n/useT';

const RUNS_PAGE_SIZE = 20;

export function SourcesPage(): React.ReactElement {
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [runsPage, setRunsPage] = useState(0);

  const sources = useQuery({
    queryKey: queryKeys.sources,
    queryFn: ({ signal }) => api.listSources(signal),
  });

  const openAlexQuota = useQuery({
    queryKey: queryKeys.openAlexQuota,
    queryFn: ({ signal }) => api.getOpenAlexQuota(signal),
    staleTime: 30_000,
    refetchInterval: 60_000,
  });

  const runs = useQuery({
    queryKey: queryKeys.ingestionRuns(runsPage, RUNS_PAGE_SIZE),
    queryFn: ({ signal }) =>
      api.listIngestionRuns({ page: runsPage, size: RUNS_PAGE_SIZE }, signal),
    placeholderData: keepPreviousData,
  });

  const trigger = useMutation({
    mutationFn: (source: SourceView) => api.triggerIngestionRun(source.id, { mode: 'INCREMENTAL' }),
    onSuccess: (run) => {
      toast.show('Прогон запущен', {
        tone: 'good',
        description: `Источник ${run.sourceId}, режим ${run.mode ?? 'INCREMENTAL'}.`,
      });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sources });
      void queryClient.invalidateQueries({ queryKey: queryKeys.openAlexQuota });
      void queryClient.invalidateQueries({ queryKey: ['ingestion-runs'] });
    },
    onError: (error: unknown) => {
      const ui = toUiError(error);
      toast.show('Не удалось запустить прогон', {
        tone: 'critical',
        description: `${ui.message} ${ui.action}`,
      });
    },
  });

  const toggle = useMutation({
    mutationFn: (source: SourceView) => api.updateSource(source.id, { enabled: !source.enabled }),
    onSuccess: (source) => {
      toast.show(source.enabled ? 'Источник включён' : 'Источник отключён', { tone: 'info' });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sources });
    },
    onError: (error: unknown) => {
      const ui = toUiError(error);
      toast.show('Не удалось изменить источник', {
        tone: 'critical',
        description: `${ui.message} ${ui.action}`,
      });
    },
  });

  return (
    <PageStack>
      <PageHeader
        title="Источники данных"
        description="Состояние коннекторов и история прогонов сбора. Недоступный источник помечает результат анализа как неполный."
      />

      <Card>
        <CardHeader as="h2" title="Квота OpenAlex" />
        <CardBody>
          {openAlexQuota.isPending ? (
            <SkeletonList count={1} height={44} label={t('state.loadingLabel')} />
          ) : openAlexQuota.isError ? (
            <ErrorState
              error={openAlexQuota.error}
              onRetry={() => void openAlexQuota.refetch()}
              compact
            />
          ) : openAlexQuota.data.keysConfigured === 0 ? (
            <p>Ключи OpenAlex не настроены.</p>
          ) : openAlexQuota.data.keysChecked === 0 ? (
            <p>
              Не удалось получить остаток квоты ни по одному ключу. Проверка повторится через
              минуту.
            </p>
          ) : (
            <>
              <p style={{ fontSize: '1.5rem', fontWeight: 700, margin: 0 }}>
                {formatCount(openAlexQuota.data.remainingCredits)} /{' '}
                {formatCount(openAlexQuota.data.limitCredits)} кредитов
              </p>
              <p className="caption">
                {openAlexQuota.data.keysChecked === openAlexQuota.data.keysConfigured
                  ? `Сумма по ${openAlexQuota.data.keysConfigured} ключам.`
                  : `Данные по ${openAlexQuota.data.keysChecked} из ${openAlexQuota.data.keysConfigured} ключей; общий остаток может быть больше.`}{' '}
                {openAlexQuota.data.resetsAt
                  ? `Сброс ${formatDateTime(openAlexQuota.data.resetsAt)}.`
                  : null}{' '}
                Обновлено {formatRelativeTime(openAlexQuota.data.checkedAt)}.
              </p>
            </>
          )}
        </CardBody>
      </Card>

      <Card>
        <CardHeader as="h2" title="Коннекторы" />
        <CardBody flush>
          {sources.isPending ? (
            <div style={{ padding: '1.5rem' }}>
              <SkeletonList count={4} height={44} label={t('state.loadingLabel')} />
            </div>
          ) : sources.isError ? (
            <div style={{ padding: '1.5rem' }}>
              <ErrorState error={sources.error} onRetry={() => void sources.refetch()} compact />
            </div>
          ) : sources.data.length === 0 ? (
            <EmptyState compact icon="database" title="Источники не настроены" />
          ) : (
            <Table caption="Источники данных и их состояние">
              <thead>
                <tr>
                  <Th>Источник</Th>
                  <Th>Класс</Th>
                  <Th>Состояние</Th>
                  <Th numeric>Документов</Th>
                  <Th numeric>Лимит, /мин</Th>
                  <Th>Последний прогон</Th>
                  <Th>Действия</Th>
                </tr>
              </thead>
              <tbody>
                {sources.data.map((source) => (
                  <tr key={source.id}>
                    <Td primary>
                      {source.displayName}
                      <div
                        style={{
                          fontFamily: 'var(--default-mono-font-family, ui-monospace, monospace)',
                          fontSize: '0.75rem',
                          color: 'var(--muted-foreground)',
                        }}
                      >
                        {source.id}
                      </div>
                    </Td>
                    <Td>
                      <SourceClassBadge sourceClass={source.sourceClass} />
                    </Td>
                    <Td>
                      <span style={{ display: 'flex', flexWrap: 'wrap', gap: '0.25rem' }}>
                        <Badge
                          tone={source.enabled ? 'good' : 'neutral'}
                          icon={source.enabled ? 'checkCircle' : 'stop'}
                        >
                          {source.enabled ? 'включён' : 'отключён'}
                        </Badge>
                        {source.requiresApiKey && source.apiKeyConfigured === false ? (
                          <Badge tone="warning" icon="alert">
                            нет API-ключа
                          </Badge>
                        ) : null}
                      </span>
                    </Td>
                    <Td numeric>{formatCount(source.documentCount)}</Td>
                    <Td numeric>{formatCount(source.rateLimitPerMinute)}</Td>
                    <Td>
                      {source.lastRun ? (
                        <span
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '0.25rem',
                          }}
                        >
                          <RunStatusBadge status={source.lastRun.status} />
                          <time
                            dateTime={toDateTimeAttr(source.lastRun.startedAt)}
                            style={{ fontSize: '0.75rem' }}
                          >
                            {formatRelativeTime(source.lastRun.startedAt)}
                          </time>
                        </span>
                      ) : (
                        t('common.never')
                      )}
                    </Td>
                    <Td>
                      <span style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                        <Button
                          size="sm"
                          icon="play"
                          disabled={!source.enabled}
                          loading={trigger.isPending && trigger.variables?.id === source.id}
                          onClick={() => trigger.mutate(source)}
                        >
                          Запустить
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          loading={toggle.isPending && toggle.variables?.id === source.id}
                          onClick={() => toggle.mutate(source)}
                        >
                          {source.enabled ? 'Отключить' : 'Включить'}
                        </Button>
                      </span>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </CardBody>
      </Card>

      <Card>
        <CardHeader as="h2" title="История прогонов" />
        <CardBody flush>
          {runs.isPending ? (
            <div style={{ padding: '1.5rem' }}>
              <SkeletonList count={4} height={40} label={t('state.loadingLabel')} />
            </div>
          ) : runs.isError ? (
            <div style={{ padding: '1.5rem' }}>
              <ErrorState error={runs.error} onRetry={() => void runs.refetch()} compact />
            </div>
          ) : runs.data.content.length === 0 ? (
            <EmptyState compact icon="clock" title="Прогонов ещё не было" />
          ) : (
            <>
              <Table caption="История прогонов сбора">
                <thead>
                  <tr>
                    <Th>Источник</Th>
                    <Th>Режим</Th>
                    <Th>Статус</Th>
                    <Th numeric>Получено</Th>
                    <Th numeric>Создано</Th>
                    <Th numeric>Дубли</Th>
                    <Th numeric>Отклонено</Th>
                    <Th>Начат</Th>
                  </tr>
                </thead>
                <tbody>
                  {runs.data.content.map((run) => (
                    <tr key={run.id}>
                      <Td primary nowrap>
                        {run.sourceId}
                      </Td>
                      <Td nowrap>{run.mode ? t(`runMode.${run.mode}`) : '—'}</Td>
                      <Td>
                        <RunStatusBadge status={run.status} />
                        {run.errorCode ? (
                          <div style={{ fontSize: '0.75rem', color: 'var(--destructive)' }}>
                            {run.errorCode}
                          </div>
                        ) : null}
                      </Td>
                      <Td numeric>{formatCount(run.documentsFetched)}</Td>
                      <Td numeric>{formatCount(run.documentsCreated)}</Td>
                      <Td numeric>{formatCount(run.documentsDuplicate)}</Td>
                      <Td numeric>{formatCount(run.documentsRejected)}</Td>
                      <Td nowrap>
                        <time dateTime={toDateTimeAttr(run.startedAt)}>
                          {formatDateTime(run.startedAt)}
                        </time>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={runs.data.page}
                size={runs.data.size}
                totalElements={runs.data.totalElements}
                totalPages={runs.data.totalPages}
                onPageChange={setRunsPage}
                busy={runs.isFetching}
              />
            </>
          )}
        </CardBody>
      </Card>
    </PageStack>
  );
}
