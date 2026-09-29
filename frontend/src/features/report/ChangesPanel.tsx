/**
 * «Изменения» — остались, новые и выбывшие темы с движением мест.
 *
 * Сравнение считается сервером по двум неизменяемым версиям отчёта. Когда сменилась бо́льшая часть
 * списка, над сравнением — предупреждение: причина чаще в сборе, чем в рынке. Без него смена
 * двенадцати тем из пятнадцати читалась бы как перемена на рынке.
 */
import { Link } from 'react-router-dom';
import type { ReportDelta, ReportDeltaEntry, TrendReport } from '@/api/types';
import { useReport } from '@/api/queries';
import { formatCount } from '@/lib/format';
import { documents } from '@/lib/words';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { retryPath } from '@/features/research/retryPath';
import { topicTitle } from './topicModel';

const UNAVAILABLE: Record<string, string> = {
  'no-previous-version':
    'Это первый прогон по направлению. Изменения появятся после следующего запуска.',
};

function Entry({
  report,
  entry,
  move,
  linkReportId,
}: {
  report: TrendReport | undefined;
  entry: ReportDeltaEntry;
  move?: boolean;
  linkReportId: string | undefined;
}): React.ReactElement {
  const trend = report?.trends.find((item) => item.trendKey === entry.trendKey);
  const title = trend ? topicTitle(trend) : { title: entry.title, original: null };
  const previous =
    entry.rankChange !== null && entry.rankChange !== undefined
      ? entry.rank + entry.rankChange
      : null;
  const content = (
    <>
      <span className="r">{entry.rank}</span>
      <span>
        <span className="t" lang={title.original ? undefined : 'en'}>
          {title.title}
        </span>
        {title.original ? (
          <span className="o" lang="en">
            {title.original}
          </span>
        ) : null}
      </span>
      <span className="mv">
        {move && previous !== null && previous !== entry.rank ? (
          <>
            <HjIcon name={previous > entry.rank ? 'arrow-up' : 'arrow-down'} size={14} />
            <span aria-hidden="true">
              {previous} → {entry.rank}
            </span>
            <span className="sr-only">
              было место {previous}, стало {entry.rank}
            </span>
          </>
        ) : move && previous === entry.rank ? (
          <span className="caption">на месте</span>
        ) : null}
      </span>
    </>
  );
  return (
    <li>
      {linkReportId ? (
        <Link to={`/reports/${linkReportId}/trends/${encodeURIComponent(entry.trendKey)}`}>
          {content}
        </Link>
      ) : (
        <div className="gone">{content}</div>
      )}
    </li>
  );
}

export function ChangesPanel({
  report,
  delta,
  error,
  onRetry,
}: {
  report: TrendReport;
  delta: ReportDelta | undefined;
  error: unknown;
  onRetry: () => void;
}): React.ReactElement {
  const previous = useReport(delta?.previousReportId);

  if (error) return <ErrorPanel error={error} onRetry={onRetry} />;
  if (!delta) {
    return (
      <p className="caption" role="status">
        Сравниваем с прошлым прогоном…
      </p>
    );
  }
  if (delta.unavailableReason || !delta.previousReportId) {
    return (
      <div className="empty">
        <h2 className="h3">Сравнивать не с чем</h2>
        <p className="body-muted">
          {UNAVAILABLE[delta.unavailableReason ?? ''] ??
            'Прошлой версии этого отчёта нет или она недоступна. Изменения появятся после следующего запуска.'}
        </p>
        <Link
          className="btn btn--secondary"
          to={retryPath(report.query, { mode: report.mode ?? 'fast' })}
        >
          Запустить заново
        </Link>
      </div>
    );
  }

  const total = report.trends.length;
  const big = delta.entered.length >= total / 2;
  const before = previous.data?.coverage.documentsAnalyzed;
  const now = report.coverage.documentsAnalyzed;
  const change = before ? (now - before) / before : null;

  return (
    <>
      <div className={big ? 'notice notice--strong' : 'notice'}>
        <HjIcon name="info" />
        <div className="od-stack" style={{ '--od-gap': '4px' } as React.CSSProperties}>
          <strong>
            Сравнение с версией {delta.previousVersion ?? 'прошлого прогона'}
            {before !== undefined && change !== null
              ? `: ${formatCount(before)} → ${documents(now)} (${change >= 0 ? '+' : '−'}${Math.round(Math.abs(change) * 100)} %)`
              : ''}
          </strong>
          <span>
            {big
              ? `Сменилось ${delta.entered.length} тем из ${total}. Когда меняется бо́льшая часть списка, причина чаще в сборе, чем в рынке, — опирайтесь на темы, которые держатся в обоих прогонах.`
              : `Сменилось ${delta.entered.length} тем из ${total}.`}
          </span>
        </div>
      </div>
      <div className="changes">
        <section className="change-col" aria-labelledby="ch-kept">
          <h3 className="h4" id="ch-kept">
            Остались · {delta.stayed.length}
          </h3>
          {delta.stayed.length > 0 ? (
            <ul className="change-list">
              {delta.stayed.map((entry) => (
                <Entry
                  key={entry.trendKey}
                  report={report}
                  entry={entry}
                  move
                  linkReportId={report.id}
                />
              ))}
            </ul>
          ) : (
            <p className="body-muted">Ни одна тема не удержалась.</p>
          )}
        </section>
        <section className="change-col" aria-labelledby="ch-new">
          <h3 className="h4" id="ch-new">
            Новые · {delta.entered.length}
          </h3>
          <ul className="change-list">
            {delta.entered.map((entry) => (
              <Entry key={entry.trendKey} report={report} entry={entry} linkReportId={report.id} />
            ))}
          </ul>
        </section>
        <section className="change-col" aria-labelledby="ch-gone">
          <h3 className="h4" id="ch-gone">
            Выбыли · {delta.left.length}
          </h3>
          <ul className="change-list">
            {delta.left.map((entry) => (
              <Entry
                key={entry.trendKey}
                report={previous.data}
                entry={entry}
                linkReportId={delta.previousReportId}
              />
            ))}
          </ul>
          <p className="mt-4">
            <Link className="link" to={`/reports/${delta.previousReportId}`}>
              Открыть прошлый отчёт
            </Link>
          </p>
        </section>
      </div>
    </>
  );
}
