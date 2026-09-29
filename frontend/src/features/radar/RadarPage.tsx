/**
 * Радар — «что изменилось в направлениях, которые вы отслеживаете».
 *
 * Строится из последних готовых отчётов по каждому направлению и сравнения каждого с предыдущей
 * версией (`/reports/{id}/delta`). Главное с прошлого прогона — не придуманные тезисы, а три
 * вывода, которые следуют из чисел: где сменилась бо́льшая часть списка, какие отчёты неполные,
 * какой отчёт свежий.
 *
 * Отслеживаемые направления — сохранённые на сервере. Пока ни одно не отмечено, радар показывает
 * все направления с готовыми отчётами: пустой первый экран не отвечает ни на один вопрос.
 */
import { useMemo } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { api } from '@/api/client';
import { latestReports, useRecentRequests, useReport } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import type { ReportDelta, ResearchRequestView, TrendReport } from '@/api/types';
import { RelTag, SkelLine } from '@/features/report/TopicBits';
import { ErrorPanel } from '@/ui/ErrorPanel';
import {
  directionTitle,
  sourceCoverage,
  sourceName,
  topicTitle,
} from '@/features/report/topicModel';
import { formatCount, plural, pluralize } from '@/lib/format';
import { INTRO_KEY, WATCH_KEY, topicId, useLocal, type TopicSnapshot } from '@/lib/localState';
import { DIRECTIONS, DOCUMENTS, TOPICS, documents, shortDate } from '@/lib/words';
import { useFeature } from '@/lib/features';
import { useDialog } from '@/ui/DialogProvider';
import { HjIcon } from '@/ui/HjIcon';
import { howtoDialog } from '@/app/guides';
import { RunBanner } from './RunBanner';
import { useTracking } from './useTracking';

const MAX_CARDS = 9;

interface DirectionState {
  request: ResearchRequestView;
  report: TrendReport | undefined;
  delta: ReportDelta | undefined;
  loading: boolean;
}

/** «А», «А и Б», «А, Б и В». */
function joinNames(names: string[]): string {
  return names.length <= 1
    ? (names[0] ?? '')
    : `${names.slice(0, -1).join(', ')} и ${names[names.length - 1] ?? ''}`;
}

function newCount(delta: ReportDelta | undefined): number | null {
  if (!delta || delta.unavailableReason || !delta.previousReportId) return null;
  return delta.entered.length;
}

function DirCard({
  state,
  tracked,
  onToggle,
  pending,
}: {
  state: DirectionState;
  tracked: boolean;
  onToggle: (() => void) | null;
  pending: boolean;
}): React.ReactElement {
  const { request, report, delta } = state;
  const fresh = newCount(delta);
  const coverage = report ? sourceCoverage(report) : null;
  const href = `/reports/${request.reportId ?? ''}`;
  return (
    <li className="dir-card card">
      <div className="od-row-top" style={{ '--od-gap': '12px' } as React.CSSProperties}>
        <div className="od-stack od-fill" style={{ '--od-gap': '4px' } as React.CSSProperties}>
          <h3 className="h3">
            <Link to={href}>{directionTitle(request.query)}</Link>
          </h3>
          <p className="caption">
            {request.parameters.mode === 'quality' ? 'качественный режим' : 'быстрый режим'} · отчёт
            от {shortDate(request.finishedAt ?? request.submittedAt)}
          </p>
        </div>
        {fresh !== null ? (
          <span className="tag tag--outline tag--lg od-fixed">
            {fresh} {plural(fresh, { one: 'новая', few: 'новые', many: 'новых' })}
          </span>
        ) : null}
      </div>
      {report ? (
        <ol className="mini-top" aria-label="Первые три темы">
          {report.trends.slice(0, 3).map((trend) => {
            const title = topicTitle(trend);
            return (
              <li key={trend.trendKey}>
                <span className="r">{trend.rank}</span>
                <span className="t od-truncate" lang={title.original ? undefined : 'en'}>
                  {title.title}
                </span>
              </li>
            );
          })}
        </ol>
      ) : (
        <div className="od-stack" aria-hidden="true">
          <SkelLine width="90%" height={16} />
          <SkelLine width="75%" height={16} />
          <SkelLine width="82%" height={16} />
        </div>
      )}
      <dl className="dir-meta">
        <div className="od-stat">
          <dt>документов</dt>
          <dd>{report ? formatCount(report.coverage.documentsAnalyzed) : '…'}</dd>
        </div>
        <div className="od-stat">
          <dt>источников</dt>
          <dd>{coverage ? `${coverage.used.length} из ${coverage.total}` : '…'}</dd>
        </div>
        <div className="od-stat">
          <dt>тем</dt>
          <dd>{report ? report.trends.length : '…'}</dd>
        </div>
      </dl>
      {coverage && coverage.unavailable.length > 0 ? (
        <p className="dir-card__warn">
          <HjIcon name="alert" size={16} />
          <span>
            Не ответили: {coverage.unavailable.map(sourceName).join(', ')} — отчёт неполный
          </span>
        </p>
      ) : null}
      <div className="dir-card__actions">
        <Link className="btn btn--primary btn--sm" to={href}>
          Открыть отчёт
        </Link>
        {fresh !== null ? (
          <Link className="btn btn--secondary btn--sm" to={`${href}/changes`}>
            Что изменилось
          </Link>
        ) : null}
        {onToggle ? (
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-pressed={tracked}
            disabled={pending}
            onClick={onToggle}
          >
            <HjIcon name={tracked ? 'check' : 'bell'} size={16} />
            {tracked ? 'На радаре' : 'Отслеживать'}
          </button>
        ) : null}
      </div>
    </li>
  );
}

interface Highlight {
  href: string;
  title: string;
  text: string;
  more: string;
}

function highlightsOf(states: DirectionState[], previous: Map<string, TrendReport>): Highlight[] {
  const result: Highlight[] = [];
  const withDelta = states
    .map((state) => ({ state, fresh: newCount(state.delta) }))
    .filter(
      (item): item is { state: DirectionState; fresh: number } =>
        item.fresh !== null && !!item.state.report,
    );
  const turnover = withDelta.sort(
    (a, b) =>
      b.fresh / (b.state.report?.trends.length ?? 1) -
      a.fresh / (a.state.report?.trends.length ?? 1),
  )[0];
  if (turnover && turnover.fresh > 0 && turnover.state.report) {
    const report = turnover.state.report;
    const before = turnover.state.delta?.previousReportId
      ? previous.get(turnover.state.delta.previousReportId)
      : undefined;
    const big = turnover.fresh >= report.trends.length / 2;
    const corpus = before
      ? `Корпус: ${formatCount(before.coverage.documentsAnalyzed)} → ${documents(report.coverage.documentsAnalyzed)}. `
      : '';
    result.push({
      href: `/reports/${report.id}/changes`,
      title: `${directionTitle(report.query)}: ${turnover.fresh} из ${pluralize(report.trends.length, TOPICS)} — новые`,
      text: big
        ? `${corpus}Когда меняется бо́льшая часть списка, причина чаще в сборе, чем в рынке, — опирайтесь на темы, которые остались в обоих прогонах.`
        : `${corpus}Остальные темы держатся с прошлого прогона.`,
      more: 'Что изменилось',
    });
  }

  const partial = states.filter(
    (state) => state.report && (state.report.coverage.unavailableSources ?? []).length > 0,
  );
  if (partial.length > 0) {
    const missing = new Set(
      partial.flatMap((state) => state.report?.coverage.unavailableSources ?? []),
    );
    result.push({
      href: partial.length === 1 ? `/reports/${partial[0]?.report?.id ?? ''}` : '/reports',
      title: `${joinNames(partial.map((state) => directionTitle(state.request.query)))}: ${
        partial.length === 1 ? 'отчёт неполный' : 'отчёты неполные'
      }`,
      text: `Не ответили: ${[...missing].map(sourceName).join(', ')}. Темы, которые видны только в этих источниках, могли не попасть в отчёт.`,
      more: partial.length === 1 ? 'К отчёту' : 'К отчётам',
    });
  }

  const newest = states.find((state) => state.report);
  if (newest?.report) {
    const report = newest.report;
    result.push({
      href: `/reports/${report.id}`,
      title: `Свежий отчёт: ${directionTitle(report.query)}`,
      text: `${pluralize(report.trends.length, TOPICS)} по ${formatCount(report.coverage.documentsAnalyzed)} ${plural(
        report.coverage.documentsAnalyzed,
        { one: 'документу', few: 'документам', many: 'документам' },
      )} · ${shortDate(report.generatedAt)}.`,
      more: 'Открыть отчёт',
    });
  }
  return result.slice(0, 3);
}

export function RadarPage(): React.ReactElement {
  // Радар выключается реестром фич: тогда первым экраном становится новый анализ.
  const radarOn = useFeature('radar');
  const deltaOn = useFeature('report-delta');
  const requests = useRecentRequests();
  const tracking = useTracking();
  const dialog = useDialog();
  // Подсказка первого визита помнится только браузером: входа нет, и хранить отметку «видел» на
  // сервере было бы не за кем.
  const [introDone, setIntroLocal] = useLocal<boolean>(INTRO_KEY, false);
  const setIntroDone = (): void => setIntroLocal(true);
  const [watched] = useLocal<TopicSnapshot[]>(WATCH_KEY, []);

  const directions = useMemo(() => latestReports(requests.data?.content ?? []), [requests.data]);
  const tracked = directions.filter((request) => tracking.savedFor(request.query));
  const anyTracked = tracked.length > 0;
  const shown = (anyTracked ? tracked : directions).slice(0, MAX_CARDS);
  const untracked = anyTracked
    ? directions.filter((request) => !tracking.savedFor(request.query))
    : [];

  const reports = useQueries({
    queries: shown.map((request) => ({
      queryKey: queryKeys.report(request.reportId ?? ''),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api.getReport(request.reportId ?? '', signal),
      staleTime: Infinity,
    })),
  });
  const deltas = useQueries({
    queries: shown.map((request) => ({
      queryKey: queryKeys.reportDelta(request.reportId ?? ''),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api.getReportDelta(request.reportId ?? '', signal),
      staleTime: Infinity,
      enabled: deltaOn,
    })),
  });

  const states: DirectionState[] = shown.map((request, index) => ({
    request,
    report: reports[index]?.data,
    delta: deltas[index]?.data,
    loading: !!reports[index]?.isLoading,
  }));

  // Предыдущая версия нужна одной строке «корпус: было → стало» — читаем её только для направления
  // с наибольшей сменой списка, а не для всех.
  const turnoverPrev = states
    .filter((state) => newCount(state.delta) !== null && state.report)
    .sort(
      (a, b) =>
        (newCount(b.delta) ?? 0) / (b.report?.trends.length ?? 1) -
        (newCount(a.delta) ?? 0) / (a.report?.trends.length ?? 1),
    )[0]?.delta?.previousReportId;
  const previousReport = useReport(turnoverPrev);
  const previous = new Map<string, TrendReport>();
  if (turnoverPrev && previousReport.data) previous.set(turnoverPrev, previousReport.data);
  const highlights = highlightsOf(states, previous);
  const dataReady =
    states.length > 0 &&
    states.every((state) => state.report && (!deltaOn || state.delta !== undefined));

  if (!radarOn) return <Navigate to="/new" replace />;

  return (
    <>
      <RunBanner />
      {!introDone ? (
        <div className="notice intro">
          <HjIcon name="book" />
          <div className="od-stack od-fill" style={{ '--od-gap': '8px' } as React.CSSProperties}>
            <strong>Впервые в Hiddenjem?</strong>
            <span>
              Четыре вещи, которые стоит знать до чтения отчёта: что ищет система, что писать в
              поле, сколько ждать и чему верить.
            </span>
            <div className="od-cluster">
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={() => dialog.open(howtoDialog())}
              >
                Прочитать за минуту
              </button>
            </div>
          </div>
          <button
            type="button"
            className="icon-btn"
            aria-label="Скрыть подсказку"
            onClick={setIntroDone}
          >
            <HjIcon name="x" />
          </button>
        </div>
      ) : null}

      <div className="page-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <h1 className="h1">Радар</h1>
          <p className="lead">Что изменилось в направлениях, которые вы отслеживаете.</p>
        </div>
        <Link className="btn btn--primary" to="/new">
          <HjIcon name="plus" />
          Новый анализ
        </Link>
      </div>

      {requests.isError ? (
        <ErrorPanel error={requests.error} onRetry={() => void requests.refetch()} />
      ) : null}

      {requests.isLoading ? (
        <div className="dir-grid" aria-busy="true">
          {[0, 1, 2].map((key) => (
            <div key={key} className="dir-card card" aria-hidden="true">
              <SkelLine width="60%" height={24} />
              <SkelLine width="100%" height={72} />
              <SkelLine width="100%" height={40} />
            </div>
          ))}
          <p className="sr-only" role="status">
            Загружаем радар…
          </p>
        </div>
      ) : null}

      {highlights.length > 0 ? (
        <section className="section" aria-labelledby="hl-h">
          <div className="section-head">
            <h2 className="h2" id="hl-h">
              Главное с прошлого прогона
            </h2>
            {!dataReady ? <p className="caption">Собираем сравнения…</p> : null}
          </div>
          <ul className="highlights">
            {highlights.map((item) => (
              <li key={item.title}>
                <Link className="highlight" to={item.href}>
                  <span className="highlight__title">{item.title}</span>
                  <span className="body-muted">{item.text}</span>
                  <span className="highlight__more">
                    {item.more}
                    <HjIcon name="arrow-right" size={16} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {watched.length > 0 ? (
        <section className="section" aria-labelledby="w-h">
          <div className="section-head">
            <h2 className="h2" id="w-h">
              Отслеживаемые темы
            </h2>
            <p className="caption">{pluralize(watched.length, TOPICS)}</p>
          </div>
          <ul className="watch-list">
            {watched.map((item) => (
              <li key={topicId(item)}>
                <Link to={`/reports/${item.reportId}/trends/${encodeURIComponent(item.trendKey)}`}>
                  <span className="od-stack" style={{ '--od-gap': '0px' } as React.CSSProperties}>
                    <strong lang={item.original === item.title ? 'en' : undefined}>
                      {item.title}
                    </strong>
                    <span className="caption">
                      {directionTitle(item.query)} · место {item.rank} ·{' '}
                      {pluralize(item.documents, DOCUMENTS)}
                    </span>
                  </span>
                  <RelTag level={item.reliability} />
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {directions.length > 0 ? (
        <section className="section" aria-labelledby="dirs-h">
          <div className="section-head">
            <h2 className="h2" id="dirs-h">
              {anyTracked ? 'Отслеживаемые направления' : 'Направления с готовыми отчётами'}
            </h2>
            <p className="caption">
              {anyTracked
                ? pluralize(tracked.length, DIRECTIONS)
                : 'Отметьте нужные кнопкой «Отслеживать» — они встанут первыми'}
            </p>
          </div>
          <ul className="dir-grid">
            {states.map((state) => (
              <DirCard
                key={state.request.id}
                state={state}
                tracked={!!tracking.savedFor(state.request.query)}
                pending={tracking.pending}
                onToggle={
                  tracking.enabled
                    ? () => tracking.toggle(state.request.query, state.request.parameters)
                    : null
                }
              />
            ))}
          </ul>
        </section>
      ) : requests.isSuccess ? (
        <section className="section">
          <div className="empty">
            <h2 className="h3">Отчётов пока нет</h2>
            <p className="body-muted">
              Запустите первый анализ — и здесь будет видно, что изменилось в направлении с прошлого
              прогона.
            </p>
            <Link className="btn btn--primary" to="/new">
              Новый анализ
            </Link>
          </div>
        </section>
      ) : null}

      {untracked.length > 0 ? (
        <section className="section" aria-labelledby="un-h">
          <h2 className="h3" id="un-h">
            Не отслеживаются
          </h2>
          <ul className="untracked mt-4">
            {untracked.map((request) => (
              <li key={request.id}>
                <div className="od-stack" style={{ '--od-gap': '0px' } as React.CSSProperties}>
                  <Link to={`/reports/${request.reportId ?? ''}`}>
                    <strong>{directionTitle(request.query)}</strong>
                  </Link>
                  <span className="caption">
                    отчёт от {shortDate(request.finishedAt ?? request.submittedAt)}
                  </span>
                </div>
                <button
                  type="button"
                  className="btn btn--secondary btn--sm"
                  disabled={tracking.pending}
                  onClick={() => tracking.toggle(request.query, request.parameters)}
                >
                  <HjIcon name="plus" size={16} />
                  На радар
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
