/**
 * «Что не попало» — воронка «оценено → отсеяно → вошло», причины с числами и примерами и ответ на
 * вопрос «куда делась моя тема?».
 *
 * Число без имён проверить нельзя, поэтому у каждой причины — примеры отсеянных формулировок, и по
 * ним ищет поле. Примеры — лишь образцы; полный ответ по конкретному термину даёт движок: он
 * повторяет анализ на том же срезе корпуса и называет стадию, на которой термин отброшен. Это
 * медленно (первый вопрос к отчёту — несколько минут), поэтому спрашивается только по нажатию.
 */
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import type { TermTrace, TrendReport } from '@/api/types';
import { formatCount } from '@/lib/format';
import { useFeature } from '@/lib/features';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { retryPath } from '@/features/research/retryPath';
import { detailTitle, detailValue, stageTitle } from './explainStages';
import { normalizeQuery } from './topicModel';

function Marked({ text, needle }: { text: string; needle: string }): React.ReactElement {
  const at = needle ? text.toLowerCase().indexOf(needle.toLowerCase()) : -1;
  if (at < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, at)}
      <mark>{text.slice(at, at + needle.length)}</mark>
      {text.slice(at + needle.length)}
    </>
  );
}

function Trace({ trace, stages }: { trace: TermTrace; stages: string[] }): React.ReactElement {
  const reached = stages.indexOf(trace.stage);
  const detail = Object.entries(trace.detail ?? {});
  const rule = (trace.detail ?? {})['rule'];
  return (
    <li className="trace">
      <div className="trace__head">
        <strong lang="en">{trace.term}</strong>
        <span className={trace.inReport ? 'tag tag--dark' : 'tag'}>
          {trace.inReport ? 'в отчёте' : 'отброшен'}
        </span>
        {trace.canonicalTerm && trace.canonicalTerm !== trace.term ? (
          <span className="caption">
            слит в «<span lang="en">{trace.canonicalTerm}</span>»
          </span>
        ) : null}
      </div>
      {trace.reason ? <p className="body-muted">{trace.reason}</p> : null}
      <ol className="trace__path" aria-label="Путь по конвейеру">
        {stages.map((stage, index) => {
          const passed = reached >= 0 && (index < reached || (index === reached && trace.inReport));
          const stopped = index === reached && !trace.inReport;
          return (
            <li key={stage} className={stopped ? 'is-stop' : passed ? 'is-pass' : undefined}>
              <span className="trace__dot" aria-hidden="true" />
              {stageTitle(stage)}
              {stopped ? <span className="sr-only"> — здесь отброшен</span> : null}
            </li>
          );
        })}
      </ol>
      {detail.length > 0 ? (
        <dl className="trace__detail">
          {detail.map(([key, value]) => (
            <div key={key}>
              <dt>{detailTitle(key, rule)}</dt>
              <dd>{detailValue(key, value, rule)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </li>
  );
}

export function ExcludedPanel({ report }: { report: TrendReport }): React.ReactElement {
  // Трассировка выключается реестром фич: тогда поле только подсвечивает примеры, а кнопки,
  // которая ответила бы отказом, нет вовсе.
  const termTrace = useFeature('term-trace');
  const [search, setSearch] = useState('');
  const [asked, setAsked] = useState<string[]>([]);
  const exclusions = report.coverage.exclusions ?? [];
  const needle = normalizeQuery(search);
  const excluded = exclusions.reduce((sum, item) => sum + item.count, 0);
  const max = Math.max(1, ...exclusions.map((item) => item.count));
  const suppressed = report.coverage.suppressedByAnalyst ?? 0;
  const evaluated = report.coverage.candidatesEvaluated;
  const hits = exclusions.filter((item) =>
    (item.examples ?? []).some((example) => needle && normalizeQuery(example).includes(needle)),
  );

  const explanation = useQuery({
    queryKey: queryKeys.reportExplanation(report.id, asked),
    queryFn: ({ signal }) => api.explainReportTerms(report.id, asked, signal),
    enabled: asked.length > 0,
    staleTime: Infinity,
    retry: false,
  });

  const ask = (event: FormEvent): void => {
    event.preventDefault();
    const terms = search
      .split(',')
      .map((term) => term.trim())
      .filter(Boolean)
      .slice(0, 5);
    if (terms.length > 0) setAsked(terms);
  };

  return (
    <>
      <div className="excl-summary">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <h2 className="h2">Что отсеяно и почему</h2>
          <p className="body-muted prose">
            Если знакомой темы нет в отчёте, ищите её здесь. Число без имён проверить нельзя,
            поэтому у каждой причины есть примеры отсеянных формулировок.
          </p>
        </div>
        <div className="funnel panel">
          {evaluated !== undefined ? (
            <div className="funnel__row">
              <span>Оценено кандидатов</span>
              <span className="num">{formatCount(evaluated)}</span>
            </div>
          ) : null}
          <div className="funnel__row">
            <span>Формулировок отброшено по правилам</span>
            <span className="num">{formatCount(excluded)}</span>
          </div>
          {suppressed > 0 ? (
            <div className="funnel__row">
              <span>Скрыто пометкой «не технология»</span>
              <span className="num">{suppressed}</span>
            </div>
          ) : null}
          {evaluated !== undefined && excluded > evaluated ? (
            <p className="caption">
              Отброшенных больше, чем кандидатов: правила применяются и к сырым терминам — до того,
              как из них собраны кандидаты.
            </p>
          ) : null}
          <div className="funnel__row">
            <strong>Вошло в отчёт</strong>
            <strong className="num">{report.trends.length}</strong>
          </div>
        </div>
      </div>

      <form className="excl-search" onSubmit={termTrace ? ask : (event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="excl-q">Найти среди отсеянных</label>
          <div className="excl-search__row">
            <input
              className="input"
              id="excl-q"
              type="search"
              autoComplete="off"
              placeholder="Например, firewall"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            {termTrace ? (
              <button
                type="submit"
                className="btn btn--secondary"
                disabled={search.trim().length === 0 || explanation.isFetching}
              >
                Спросить движок
              </button>
            ) : null}
          </div>
          <p className="help">
            Поле подсвечивает совпадения в примерах отсеянных формулировок.
            {termTrace ? (
              <>
                {' '}
                «Спросить движок» повторит анализ на том же срезе и покажет, на каком шаге термин
                отброшен. Первый вопрос к отчёту идёт несколько минут (дольше, если движок занят
                чужим вопросом), следующие — быстрее: срез уже пересчитан. До пяти терминов через
                запятую.
              </>
            ) : null}
          </p>
        </div>
      </form>

      {asked.length > 0 ? (
        <section className="panel mb-6" aria-live="polite" aria-labelledby="trace-h">
          <h3 className="h4" id="trace-h">
            Путь по конвейеру: {asked.join(', ')}
          </h3>
          {explanation.isFetching ? (
            <p className="caption mt-2" role="status">
              <span className="spinner spinner--inline" aria-hidden="true" /> Повторяем анализ на
              том же срезе корпуса — первый раз это несколько минут. Можно уйти на другую вкладку
              отчёта: ответ досчитается.
            </p>
          ) : null}
          {explanation.isError ? (
            <div className="mt-4">
              <ErrorPanel
                error={explanation.error}
                onRetry={() => void explanation.refetch()}
                title="Движок не ответил"
              />
            </div>
          ) : null}
          {explanation.data ? (
            explanation.data.traces.length > 0 ? (
              <ul className="traces">
                {explanation.data.traces.map((trace) => (
                  <Trace key={trace.term} trace={trace} stages={explanation.data.stages} />
                ))}
              </ul>
            ) : (
              <p className="body-muted mt-2">
                Этот термин вообще не встретился в корпусе за выбранное окно — он отсеялся раньше,
                чем конвейер начал его оценивать. Проверьте написание или расширьте окно анализа.
              </p>
            )
          ) : null}
        </section>
      ) : null}

      {exclusions.length === 0 ? (
        <div className="empty">
          <h3>Причины отсева не переданы</h3>
          <p className="body-muted">
            Отчёт выпущен до появления этого блока. Спросите движок о конкретном термине — он
            покажет, где тот отброшен.
          </p>
        </div>
      ) : (
        <ul className="excl-list">
          {exclusions.map((item) => {
            const dim = needle !== '' && !hits.includes(item);
            return (
              <li key={item.code} className={dim ? 'excl is-dim' : 'excl'}>
                <div className="excl__head">
                  <h3 className="h4">
                    {item.reason.charAt(0).toUpperCase() + item.reason.slice(1)}
                  </h3>
                  <span className="excl__count num">{formatCount(item.count)}</span>
                </div>
                <div className="excl__bar" aria-hidden="true">
                  <i style={{ width: `${((item.count / max) * 100).toFixed(1)}%` }} />
                </div>
                {(item.examples ?? []).length > 0 ? (
                  <div>
                    <span className="section-label">Примеры</span>
                    <ul className="examples" lang="en">
                      {(item.examples ?? []).map((example) => (
                        <li key={example}>
                          <Marked text={example} needle={search.trim()} />
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {needle && hits.length === 0 && exclusions.length > 0 ? (
        <p className="empty mt-6">
          Среди примеров такой формулировки нет — это лишь образцы.
          {termTrace ? <> Нажмите «Спросить движок»: он проверит термин по всему корпусу.</> : null}
        </p>
      ) : null}
      {!report.trends.length ? null : (
        <div className="notice mt-6">
          <HjIcon name="info" />
          <div>
            Мейнстримные темы можно вернуть в отчёт параметром «Включать мейнстримные темы» при
            запуске.{' '}
            <Link
              className="link"
              to={retryPath(report.query, { mode: report.mode ?? 'fast', includeMature: true })}
            >
              Запустить с мейнстримными темами
            </Link>
          </div>
        </div>
      )}
    </>
  );
}
