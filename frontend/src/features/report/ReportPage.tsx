/**
 * Отчёт — «что нового и чему верить».
 *
 * Шапка: дата, режим, документы, источники и четыре числа — тем, устойчивых при любых весах (или
 * документов, если устойчивость не измерялась), новых с прошлого прогона и надёжных без оговорок.
 * Оговорки о полноте — над числами, а не под ними: неполный отчёт и нераспознанное направление
 * меняют то, как читать всё остальное.
 *
 * Три вкладки — адресами, а не состоянием: ссылку на «Изменения» пересылают коллеге.
 */
import { useParams, useLocation, Link, useNavigate } from 'react-router-dom';
import { api } from '@/api/client';
import { useReport, useReportDelta } from '@/api/queries';
import type { TrendReport } from '@/api/types';
import { useBriefing } from '@/app/BriefingDialog';
import { howtoDialog } from '@/app/guides';
import { downloadFile } from '@/app/briefing';
import { useToast } from '@/components/toast/ToastContext';
import { useTracking } from '@/features/radar/useTracking';
import { retryPath } from '@/features/research/retryPath';
import { toUiError } from '@/lib/errors';
import { useFeature } from '@/lib/features';
import { formatCount, formatDateLong, pluralize } from '@/lib/format';
import { BRIEFING_KEY, useLocal, type TopicSnapshot } from '@/lib/localState';
import { DOCUMENTS, modeLabel } from '@/lib/words';
import { useDialog } from '@/ui/DialogProvider';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { Menu } from '@/ui/Menu';
import { ChangesPanel } from './ChangesPanel';
import { ExcludedPanel } from './ExcludedPanel';
import { SkelLine } from './TopicBits';
import { TopicsPanel, buildRows } from './TopicsPanel';
import { directionTitle, sourceCoverage, sourceName } from './topicModel';

type Tab = 'topics' | 'excluded' | 'changes';

function Skeleton(): React.ReactElement {
  return (
    <>
      <nav className="crumbs" aria-label="Путь">
        <Link to="/reports">Отчёты</Link>
      </nav>
      <div className="report-head">
        <div className="od-stack" style={{ '--od-gap': '12px' } as React.CSSProperties}>
          <SkelLine width="min(520px, 80vw)" height={40} />
          <SkelLine width="min(420px, 70vw)" height={20} />
        </div>
      </div>
      <p className="sr-only" role="status">
        Загружаем отчёт…
      </p>
      <div className="stats" aria-hidden="true">
        {[0, 1, 2, 3].map((key) => (
          <div key={key} className="od-stat" style={{ '--od-gap': '8px' } as React.CSSProperties}>
            <SkelLine width="64px" height={32} />
            <SkelLine width="140px" height={14} />
          </div>
        ))}
      </div>
      <div className="report-grid mt-8" aria-hidden="true">
        <div className="od-stack">
          {[0, 1, 2, 3, 4, 5].map((key) => (
            <SkelLine key={key} width="100%" height={72} />
          ))}
        </div>
        <div className="card preview">
          <SkelLine width="40%" height={14} />
          <SkelLine width="80%" height={32} />
          <SkelLine width="100%" height={120} />
        </div>
      </div>
    </>
  );
}

function windowYears(report: TrendReport): string | null {
  const from = report.coverage.windowFrom ?? report.portrait?.windowFrom;
  const to = report.coverage.windowTo ?? report.portrait?.windowTo;
  if (!from || !to) return null;
  return `окно ${from.slice(0, 4)}–${to.slice(0, 4)}`;
}

function ExportMenu({ report }: { report: TrendReport }): React.ReactElement {
  const toast = useToast();
  const slug = report.id.slice(0, 8);
  const run = (format: 'markdown' | 'csv' | 'json', extension: string): void => {
    api
      .exportReport(report.id, format)
      .then((blob) => {
        const name = `hiddenjem-${slug}.${extension}`;
        downloadFile(name, blob);
        toast.show(`Файл ${name} скачан`);
      })
      .catch((error: unknown) =>
        toast.show('Экспорт не удался', {
          tone: 'critical',
          description: toUiError(error).message,
        }),
      );
  };
  return (
    <Menu
      triggerClassName="btn btn--secondary btn--sm"
      trigger={
        <>
          <HjIcon name="download" size={16} />
          Экспорт
        </>
      }
      label="Экспорт отчёта"
    >
      {(close) => (
        <>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close();
              run('markdown', 'md');
            }}
          >
            <HjIcon name="file" size={18} />
            <span>
              <span>Записка для комитета</span>
              <span className="caption">Markdown · все темы с оговорками</span>
            </span>
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close();
              run('csv', 'csv');
            }}
          >
            <HjIcon name="download" size={18} />
            <span>
              <span>Таблица тем</span>
              <span className="caption">CSV для Excel</span>
            </span>
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close();
              run('json', 'json');
            }}
          >
            <HjIcon name="layers" size={18} />
            <span>
              <span>Данные отчёта</span>
              <span className="caption">JSON для скриптов</span>
            </span>
          </button>
        </>
      )}
    </Menu>
  );
}

export function ReportPage(): React.ReactElement {
  const { reportId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const requestedTab: Tab = location.pathname.endsWith('/excluded')
    ? 'excluded'
    : location.pathname.endsWith('/changes')
      ? 'changes'
      : 'topics';
  const report = useReport(reportId);
  const deltaOn = useFeature('report-delta');
  const exportOn = useFeature('report-export');
  const savedOn = useFeature('saved-domains');
  const delta = useReportDelta(reportId, deltaOn);
  // Выключенное сравнение версий не должно оставлять вкладку, которая вечно «сравнивает».
  const tab: Tab = requestedTab === 'changes' && !deltaOn ? 'topics' : requestedTab;
  const tracking = useTracking();
  const dialog = useDialog();
  const { openBriefing } = useBriefing();
  const [briefing] = useLocal<TopicSnapshot[]>(BRIEFING_KEY, []);

  if (report.isLoading) return <Skeleton />;
  if (report.isError || !report.data) {
    return (
      <>
        <nav className="crumbs" aria-label="Путь">
          <Link to="/reports">Отчёты</Link>
        </nav>
        <h1 className="h1 mb-6">Отчёт не открылся</h1>
        <ErrorPanel error={report.error} onRetry={() => void report.refetch()} />
      </>
    );
  }

  const data = report.data;
  const title = directionTitle(data.query);
  const coverage = sourceCoverage(data);
  const rows = buildRows(data, delta.data);
  const comparable = !!delta.data && !delta.data.unavailableReason && !!delta.data.previousReportId;
  const fresh = comparable ? (delta.data?.entered.length ?? 0) : null;
  const confident = data.trends.filter((trend) => trend.assessment.confidence > 0.75).length;
  const excludedTotal = (data.coverage.exclusions ?? []).reduce((sum, item) => sum + item.count, 0);
  const tracked = !!tracking.savedFor(data.query);
  const hidden = data.hiddenTopics ?? [];

  const tabs: Array<[Tab, string, string | number, string]> = [
    ['topics', 'Темы', data.trends.length, `/reports/${data.id}`],
    [
      'excluded',
      'Что не попало',
      excludedTotal > 0 ? formatCount(excludedTotal) : '—',
      `/reports/${data.id}/excluded`,
    ],
    ['changes', 'Изменения', fresh ?? '—', `/reports/${data.id}/changes`],
  ].filter(([id]) => id !== 'changes' || deltaOn) as Array<[Tab, string, string | number, string]>;

  return (
    <>
      <nav className="crumbs" aria-label="Путь">
        <Link to="/reports">Отчёты</Link>
        <HjIcon name="chevron-right" size={16} />
        <span aria-current="page">{title}</span>
      </nav>
      <div className="report-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <h1 className="h1">{title}</h1>
          <p className="meta-line">
            <span>{formatDateLong(data.generatedAt)}</span>
            <span>{modeLabel(data.mode).toLowerCase()} режим</span>
            <span className="num">{pluralize(data.coverage.documentsAnalyzed, DOCUMENTS)}</span>
            <span>
              {coverage.used.length} из {coverage.total} источников
            </span>
            {windowYears(data) ? <span>{windowYears(data)}</span> : null}
            <span>версия {data.version}</span>
          </p>
        </div>
        <div className="report-actions">
          {savedOn ? (
            <button
              type="button"
              className="btn btn--secondary btn--sm"
              aria-pressed={tracked}
              disabled={tracking.pending || tracking.loading}
              onClick={() => tracking.toggle(data.query, { mode: data.mode ?? 'fast' })}
            >
              <HjIcon name={tracked ? 'check' : 'bell'} size={16} />
              {tracked ? 'На радаре' : 'Отслеживать'}
            </button>
          ) : null}
          <button type="button" className="btn btn--secondary btn--sm" onClick={openBriefing}>
            <HjIcon name="bookmark" size={16} />
            Записка
            {briefing.length > 0 ? <span className="count">{briefing.length}</span> : null}
          </button>
          {exportOn ? <ExportMenu report={data} /> : null}
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => dialog.open(howtoDialog())}
          >
            <HjIcon name="book" size={16} />
            Как читать отчёт
          </button>
        </div>
      </div>

      {coverage.unavailable.length > 0 ? (
        <div className="notice">
          <HjIcon name="alert" />
          <div className="od-stack" style={{ '--od-gap': '4px' } as React.CSSProperties}>
            <strong>
              Отчёт неполный: ответили {coverage.used.length} из {coverage.total} источников
            </strong>
            <span>
              Не ответили: {coverage.unavailable.map(sourceName).join(', ')}. Темы, которые видны
              только в них, могли не попасть в отчёт.{' '}
              <Link className="link" to={retryPath(data.query, { mode: data.mode ?? 'fast' })}>
                Пересчитать
              </Link>
            </span>
          </div>
        </div>
      ) : null}

      {data.coverage.corpusTruncated ? (
        <div className="notice">
          <HjIcon name="info" />
          <div>
            Корпус обрезан пределом объёма: часть литературы направления не рассматривалась. Узкая
            формулировка направления уменьшит обрезку.
          </div>
        </div>
      ) : null}

      {hidden.length > 0 ? (
        <div className="notice">
          <HjIcon name="eye-off" />
          <div>
            Скрыто тем вашей пометкой «не технология»: {hidden.length} (
            <span lang="en">{hidden.map((topic) => topic.title).join(', ')}</span>).
          </div>
        </div>
      ) : null}

      <div className="stats">
        <Link className="od-stat stat-link" to={`/reports/${data.id}/excluded`}>
          <span className="stat-num">
            {data.coverage.candidatesEvaluated !== undefined
              ? formatCount(data.coverage.candidatesEvaluated)
              : '—'}
          </span>
          <span className="stat-cap">кандидатов рассмотрено — к списку отсеянных</span>
        </Link>
        <div className="od-stat">
          <span className="stat-num">{data.trends.length}</span>
          <span className="stat-cap">слабых сигналов в отчёте</span>
        </div>
        <div className="od-stat">
          <span className="stat-num">{formatCount(data.coverage.documentsAnalyzed)}</span>
          <span className="stat-cap">
            документов обработано, {coverage.used.length} из {coverage.total} источников
          </span>
        </div>
        <div className="od-stat">
          <span className="stat-num">
            {confident}
            <small> из {data.trends.length}</small>
          </span>
          <span className="stat-cap">с уверенностью модели выше 75 %</span>
        </div>
      </div>

      <div className="tabs" role="tablist" aria-label="Разделы отчёта">
        {tabs.map(([id, label, count, href], index) => (
          <Link
            key={id}
            className="tab"
            role="tab"
            id={`tab-${id}`}
            to={href}
            replace
            aria-selected={tab === id}
            aria-controls="tabpanel"
            tabIndex={tab === id ? 0 : -1}
            onKeyDown={(event) => {
              if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
              event.preventDefault();
              const next =
                tabs[(index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length];
              if (next) {
                navigate(next[3], { replace: true });
                requestAnimationFrame(() => document.getElementById(`tab-${next[0]}`)?.focus());
              }
            }}
          >
            {label} <span className="n">{count}</span>
          </Link>
        ))}
      </div>
      <div className="tabpanel" role="tabpanel" id="tabpanel" aria-labelledby={`tab-${tab}`}>
        {tab === 'topics' ? <TopicsPanel report={data} rows={rows} /> : null}
        {tab === 'excluded' ? <ExcludedPanel report={data} /> : null}
        {tab === 'changes' ? (
          <ChangesPanel
            report={data}
            delta={delta.data}
            error={delta.error}
            onRetry={() => void delta.refetch()}
          />
        ) : null}
      </div>
    </>
  );
}
