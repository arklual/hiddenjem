/**
 * Ход анализа.
 *
 * Прогресс приходит потоком событий (SSE), при обрыве — опросом; обе дороги проверяются одной
 * схемой контракта. Стадии — те, что называет сервер: очередь, сбор, анализ, сборка. Процент,
 * сообщение текущей стадии и оценка остатка — его же. Ничего не симулируется: если сервер молчит,
 * экран показывает последнее, что знает.
 *
 * «Можно не ждать»: анализ идёт на сервере, и ушедшему со страницы придёт уведомление (RunWatcher).
 * Отмена требует подтверждения — собранное при отмене не сохраняется.
 */
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import { useReport } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import {
  ANALYSIS_STAGES,
  isTerminalStatus,
  type AnalysisStage,
  type ResearchRequestView,
} from '@/api/types';
import { useToast } from '@/components/toast/ToastContext';
import { directionTitle, sourceCoverage, sourceName } from '@/features/report/topicModel';
import { formatCount, formatStopwatch, plural, pluralize } from '@/lib/format';
import { translate } from '@/lib/i18n/translate';
import { RUNS_KEY, readLocal, writeLocal, type TrackedRun } from '@/lib/localState';
import { MINUTES, TOPICS, modeLabel } from '@/lib/words';
import { useDialog } from '@/ui/DialogProvider';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { correctionFor, explainFailure } from './failure';
import { retryPath } from './retryPath';
import { useResearchProgress } from './useResearchProgress';

const SHOWN_STAGES: ReadonlyArray<{ id: AnalysisStage; title: string; hint: string }> = [
  {
    id: 'COLLECTING',
    title: 'Сбор документов',
    hint: 'Публикации, патенты, код и отраслевые источники — параллельно. Источники ограничивают частоту запросов, поэтому это самая долгая стадия.',
  },
  {
    id: 'ANALYZING',
    title: 'Анализ',
    hint: 'Термины извлекаются и кластеризуются, для каждой темы считаются признаки зарождения.',
  },
  {
    id: 'ASSEMBLING',
    title: 'Сборка отчёта',
    hint: 'Темы ранжируются, подбираются источники-основания и кейсы, строится русский слой.',
  },
];

function stageIndex(stage: AnalysisStage): number {
  return ANALYSIS_STAGES.indexOf(stage);
}

function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);
  return now;
}

function forgetRun(requestId: string): void {
  writeLocal(
    RUNS_KEY,
    readLocal<TrackedRun[]>(RUNS_KEY, []).filter((run) => run.requestId !== requestId),
  );
}

function DonePanel({ view }: { view: ResearchRequestView }): React.ReactElement {
  const report = useReport(view.reportId ?? undefined);
  const coverage = report.data ? sourceCoverage(report.data) : null;
  return (
    <div className="run-done">
      <p className="h3">
        {report.data
          ? `Готово: ${pluralize(report.data.trends.length, TOPICS)} по ${formatCount(report.data.coverage.documentsAnalyzed)} ${plural(
              report.data.coverage.documentsAnalyzed,
              { one: 'документу', few: 'документам', many: 'документам' },
            )}`
          : 'Готово: отчёт собран'}
      </p>
      {coverage ? (
        <p>
          {coverage.unavailable.length > 0
            ? `Ответили ${coverage.used.length} из ${coverage.total} источников: не ответили ${coverage.unavailable
                .map(sourceName)
                .join(', ')}. Отчёт помечен как неполный.`
            : `Ответили все ${coverage.total} источников.`}
        </p>
      ) : null}
      <div className="od-cluster">
        <Link className="btn btn--secondary" to={`/reports/${view.reportId ?? ''}`}>
          Открыть отчёт
          <HjIcon name="arrow-right" size={18} />
        </Link>
      </div>
    </div>
  );
}

function FailurePanel({ view }: { view: ResearchRequestView }): React.ReactElement {
  const explanation = explainFailure(view.failure?.code);
  const correction = correctionFor(view.failure?.code, view.parameters);
  return (
    <div className="notice notice--danger mb-8" role="alert">
      <HjIcon name="alert" />
      <div className="od-stack od-fill" style={{ '--od-gap': '8px' } as React.CSSProperties}>
        <strong>{explanation.title}</strong>
        <span>{explanation.explanation}</span>
        <span>{explanation.nextStep}</span>
        {correction ? <span className="caption">{correction.note}</span> : null}
        <div className="od-cluster">
          {correction ? (
            <Link
              className="btn btn--primary btn--sm"
              to={retryPath(view.query, correction.parameters)}
            >
              Повторить с поправкой
            </Link>
          ) : null}
          {view.failure?.retryable !== false ? (
            <Link
              className={`btn btn--sm ${correction ? 'btn--secondary' : 'btn--primary'}`}
              to={retryPath(view.query, view.parameters)}
            >
              Повторить как есть
            </Link>
          ) : null}
        </div>
        {view.failure ? (
          <details className="error-details">
            <summary>Подробности для поддержки</summary>
            <pre>
              {view.failure.code}
              {'\n'}
              {view.failure.message}
            </pre>
          </details>
        ) : null}
      </div>
    </div>
  );
}

export function RunPage(): React.ReactElement {
  const { requestId = '' } = useParams();
  const queryClient = useQueryClient();
  const dialog = useDialog();
  const toast = useToast();

  const initial = useQuery({
    queryKey: queryKeys.researchRequest(requestId),
    queryFn: ({ signal }) => api.getResearchRequest(requestId, signal),
    enabled: Boolean(requestId),
  });
  const progress = useResearchProgress(initial.data ? requestId : undefined, initial.data);
  const view = progress.view ?? initial.data ?? null;
  const finished = view ? isTerminalStatus(view.status) : false;
  const now = useNow(!finished);

  useEffect(() => {
    if (view && isTerminalStatus(view.status)) {
      forgetRun(view.id);
      void queryClient.invalidateQueries({ queryKey: ['research-requests'] });
    }
  }, [view, queryClient]);

  const cancel = useMutation({
    mutationFn: () => api.cancelResearchRequest(requestId),
    onSuccess: (next) => {
      queryClient.setQueryData(queryKeys.researchRequest(requestId), next);
      forgetRun(requestId);
      toast.show('Анализ отменён');
    },
    onError: () => toast.show('Не удалось отменить анализ', { tone: 'critical' }),
  });

  if (initial.isLoading) {
    return (
      <p className="caption" role="status">
        Загружаем ход анализа…
      </p>
    );
  }
  if (initial.isError || !view) {
    return (
      <>
        <nav className="crumbs" aria-label="Путь">
          <Link to="/new">Новый анализ</Link>
        </nav>
        <h1 className="h1 mb-6">Анализ не найден</h1>
        <ErrorPanel error={initial.error} onRetry={() => void initial.refetch()} />
      </>
    );
  }

  const title = directionTitle(view.query);
  const current = stageIndex(view.progress.stage);
  const done = view.status === 'COMPLETED';
  const percent = done ? 100 : Math.max(0, Math.min(100, view.progress.percent));
  const elapsedSeconds = Math.max(
    0,
    Math.floor(
      ((view.finishedAt ? new Date(view.finishedAt).getTime() : now) -
        new Date(view.submittedAt).getTime()) /
        1000,
    ),
  );
  const etaMinutes = view.etaSeconds ? Math.max(1, Math.ceil(view.etaSeconds / 60)) : null;

  const confirmCancel = (): void => {
    dialog.open({
      title: 'Отменить анализ?',
      body: (
        <p>
          Собранные документы не сохранятся: чтобы получить отчёт, анализ придётся запускать заново.
        </p>
      ),
      actions: [
        { label: 'Продолжить анализ', autoFocus: true },
        { label: 'Отменить анализ', primary: true, onClick: () => cancel.mutate() },
      ],
    });
  };

  return (
    <>
      <nav className="crumbs" aria-label="Путь">
        <Link to="/new">Новый анализ</Link>
        <HjIcon name="chevron-right" size={16} />
        <span aria-current="page">{title}</span>
      </nav>
      <div className="page-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <p className="eyebrow">
            {modeLabel(view.parameters.mode)} режим ·{' '}
            {pluralize(view.parameters.topN ?? 15, TOPICS)} · окно{' '}
            {pluralize(view.parameters.yearsWindow ?? 7, { one: 'год', few: 'года', many: 'лет' })}
          </p>
          <h1 className="h1">{title}</h1>
          <p className="caption">Запрос «{view.query}»</p>
        </div>
        {!finished ? (
          <button
            type="button"
            className="btn btn--secondary"
            onClick={confirmCancel}
            disabled={cancel.isPending}
          >
            Отменить анализ
          </button>
        ) : null}
      </div>

      <div className="run-layout">
        <section className="run-main card" aria-labelledby="run-h">
          <h2 className="sr-only" id="run-h">
            Ход анализа
          </h2>
          {done ? <DonePanel view={view} /> : null}
          {view.status === 'FAILED' ? <FailurePanel view={view} /> : null}
          {view.status === 'CANCELLED' ? (
            <div className="notice mb-8">
              <HjIcon name="x" />
              <div className="od-stack" style={{ '--od-gap': '6px' } as React.CSSProperties}>
                <strong>Анализ отменён</strong>
                <span>
                  Отчёт не собран.{' '}
                  <Link className="link" to={retryPath(view.query, view.parameters)}>
                    Запустить заново
                  </Link>
                </span>
              </div>
            </div>
          ) : null}

          <div className="run-progress">
            <div className="run-progress__nums">
              <span className="run-progress__pct num">{percent} %</span>
              <span className="caption num">
                {finished
                  ? `Заняло ${formatStopwatch(elapsedSeconds)}`
                  : `Прошло ${formatStopwatch(elapsedSeconds)}${
                      etaMinutes ? ` · осталось около ${pluralize(etaMinutes, MINUTES)}` : ''
                    }`}
              </span>
            </div>
            <div
              className={finished ? 'bar' : 'bar is-working'}
              role="progressbar"
              aria-label="Готовность анализа"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent}
            >
              <div className="bar__fill" style={{ transform: `scaleX(${percent / 100})` }} />
            </div>
          </div>

          <ol className="stages">
            {view.progress.stage === 'QUEUED' && !finished ? (
              <li className="stage-item is-active">
                <span className="stage-dot" aria-hidden="true">
                  <span className="spinner" />
                </span>
                <div className="stage-body">
                  <div className="stage-body__head">
                    <span className="stage-body__title">В очереди</span>
                    <span className="stage-body__state">ждём свободного обработчика</span>
                  </div>
                  <p className="caption">
                    {view.progress.message ?? translate('stageHint.QUEUED')}
                  </p>
                </div>
              </li>
            ) : null}
            {SHOWN_STAGES.map((stage) => {
              const index = stageIndex(stage.id);
              const state =
                done || current > index
                  ? 'done'
                  : current === index && !finished
                    ? 'active'
                    : 'pending';
              return (
                <li key={stage.id} className={`stage-item is-${state}`}>
                  <span className="stage-dot" aria-hidden="true">
                    {state === 'done' ? (
                      <HjIcon name="check" size={16} />
                    ) : state === 'active' ? (
                      <span className="spinner" />
                    ) : null}
                  </span>
                  <div className="stage-body">
                    <div className="stage-body__head">
                      <span className="stage-body__title">{stage.title}</span>
                      <span className="stage-body__state">
                        {state === 'done'
                          ? 'готово'
                          : state === 'active'
                            ? 'идёт…'
                            : finished
                              ? 'не выполнялась'
                              : 'ожидает'}
                      </span>
                    </div>
                    <p className="caption">{stage.hint}</p>
                    {state === 'active' && view.progress.message ? (
                      <p className="stage-message">{view.progress.message}</p>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
        </section>

        <aside className="run-aside" aria-label="Пока идёт анализ">
          <section className="panel od-stack" style={{ '--od-gap': '12px' } as React.CSSProperties}>
            <h2 className="h4">Можно не ждать</h2>
            <p className="caption">
              Анализ идёт на сервере. Уйдёте со страницы — отчёт появится в «Отчётах», а мы покажем
              уведомление, когда всё будет готово.
            </p>
            <div className="od-cluster">
              <Link className="btn btn--secondary btn--sm" to="/radar">
                На радар
              </Link>
              <Link className="btn btn--secondary btn--sm" to="/reports">
                К отчётам
              </Link>
            </div>
          </section>
          <section
            className="panel panel--warm od-stack"
            style={{ '--od-gap': '8px' } as React.CSSProperties}
          >
            <h2 className="h4">Почему это минуты, а не секунды</h2>
            <p className="caption">
              Hiddenjem соблюдает ограничения источников: arXiv просит не чаще одного запроса в три
              секунды. Зато поиск идёт по живым данным, а не по заранее собранному набору.
            </p>
          </section>
        </aside>
      </div>
    </>
  );
}
