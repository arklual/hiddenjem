/**
 * Отчёты — все запуски анализа, свои и коллег. Готовый отчёт открывается сразу и не тратит квоту.
 *
 * «Только мои» и «вся организация» — оба явным параметром: серверное умолчание — организация, и
 * выразить нужно оба ответа, а не один через отсутствие параметра.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { isRunning } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import type { ResearchRequestView } from '@/api/types';
import { directionTitle } from '@/features/report/topicModel';
import { pluralize } from '@/lib/format';
import { translate } from '@/lib/i18n/translate';
import { RUNS, modeLabel, runMinutes, whenDone } from '@/lib/words';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { explainFailure } from './failure';

const PAGE = 25;

function Status({ request }: { request: ResearchRequestView }): React.ReactElement {
  if (request.status === 'COMPLETED') {
    return request.partial ? (
      <div className="status">
        <span>
          <HjIcon name="alert" size={16} />
          Готов частично
        </span>
        <small>часть источников не ответила</small>
      </div>
    ) : (
      <div className="status">
        <span>
          <HjIcon name="check" size={16} />
          Готов
        </span>
        <small>{request.fromCache ? 'готовый результат' : 'все источники ответили'}</small>
      </div>
    );
  }
  if (request.status === 'FAILED') {
    return (
      <div className="status">
        <span>
          <HjIcon name="x" size={16} />
          Не выполнен
        </span>
        <small>{explainFailure(request.failure?.code).title}</small>
      </div>
    );
  }
  if (request.status === 'CANCELLED') {
    return (
      <div className="status">
        <span>
          <HjIcon name="minus" size={16} />
          Отменён
        </span>
      </div>
    );
  }
  return (
    <div className="status">
      <span>
        <HjIcon name="clock" size={16} />
        Идёт · {request.progress.percent} %
      </span>
      <small>
        {translate(`stage.${request.progress.stage}`)} ·{' '}
        <Link className="link" to={`/runs/${request.id}`}>
          смотреть ход
        </Link>
      </small>
    </div>
  );
}

export function ReportsPage(): React.ReactElement {
  const [size, setSize] = useState(PAGE);
  // Организация одна, вход не нужен: список — это все запуски, без деления на «мои» и «чужие».
  const requests = useQuery({
    queryKey: queryKeys.researchRequests(0, size),
    queryFn: ({ signal }) => api.listResearchRequests({ page: 0, size }, signal),
    placeholderData: (previous) => previous,
    refetchInterval: (query) => (query.state.data?.content.some(isRunning) ? 15_000 : false),
  });

  const rows = requests.data?.content ?? [];
  const total = requests.data?.totalElements ?? 0;

  return (
    <>
      <div className="page-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <h1 className="h1">Отчёты</h1>
          <p className="lead">
            Все запуски анализа. Готовый отчёт открывается сразу и не тратит квоту.
          </p>
        </div>
        <Link className="btn btn--primary" to="/new">
          <HjIcon name="plus" />
          Новый анализ
        </Link>
      </div>

      <div className="toolbar">
        {requests.data ? <p className="caption">{pluralize(total, RUNS)}</p> : null}
      </div>

      {requests.isError ? (
        <ErrorPanel error={requests.error} onRetry={() => void requests.refetch()} />
      ) : null}
      {requests.isLoading ? (
        <p className="caption" role="status">
          Загружаем запуски…
        </p>
      ) : null}

      {requests.isSuccess && rows.length === 0 ? (
        <div className="empty mt-6">
          <h2 className="h3">Запусков пока нет</h2>
          <p className="body-muted">Запустите первый анализ — отчёт появится здесь.</p>
          <Link className="btn btn--primary" to="/new">
            Новый анализ
          </Link>
        </div>
      ) : null}

      {rows.length > 0 ? (
        <div className="table-wrap">
          <table className="table">
            <caption className="sr-only">Запуски анализа</caption>
            <thead>
              <tr>
                <th scope="col">Направление</th>
                <th scope="col">Когда</th>
                <th scope="col">Режим</th>
                <th scope="col" className="num">
                  Время
                </th>
                <th scope="col">Статус</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((request) => {
                const minutes = runMinutes(request.submittedAt, request.finishedAt);
                const href =
                  request.status === 'COMPLETED' && request.reportId
                    ? `/reports/${request.reportId}`
                    : `/runs/${request.id}`;
                return (
                  <tr key={request.id}>
                    <td data-label="Направление">
                      <Link className="dir-link" to={href}>
                        {directionTitle(request.query)}
                      </Link>
                      {request.normalizedQuery && request.normalizedQuery !== request.query ? (
                        <span className="q">понято как «{request.normalizedQuery}»</span>
                      ) : null}
                    </td>
                    <td data-label="Когда">
                      {whenDone(request.finishedAt ?? request.submittedAt)}
                    </td>
                    <td data-label="Режим">{modeLabel(request.parameters.mode)}</td>
                    <td data-label="Время" className="num">
                      {minutes ? `${minutes} мин` : '—'}
                    </td>
                    <td data-label="Статус">
                      <Status request={request} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {rows.length < total ? (
        <div className="mt-6">
          <button
            type="button"
            className="btn btn--secondary"
            disabled={requests.isFetching}
            onClick={() => setSize((value) => value + PAGE)}
          >
            {requests.isFetching ? 'Загружаем…' : 'Показать ещё'}
          </button>
        </div>
      ) : null}
    </>
  );
}
