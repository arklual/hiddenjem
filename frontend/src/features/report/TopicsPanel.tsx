/**
 * Темы отчёта: слева список, справа превью выбранной — разбор без прыжков между страницами.
 *
 * Строка темы — ранг, русское название, оригинал, стадия, одна метка надёжности, «новая» и балл;
 * оговорки живут в превью. Пунктирная черта отделяет устойчивую верхушку от хвоста, чьё место
 * зависит от весов, — только когда устойчивость измерялась. Навигация стрелками или J/K, Enter
 * открывает карточку. На узком экране превью нет, и строка сразу ведёт в карточку.
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { RankedTrend, TrendReport } from '@/api/types';
import { useBriefing } from '@/app/BriefingDialog';
import { BRIEFING_KEY, topicId, useLocal, type TopicSnapshot } from '@/lib/localState';
import { pluralize } from '@/lib/format';
import { DOCUMENTS, TOPICS } from '@/lib/words';
import { useFeature } from '@/lib/features';
import { HjIcon } from '@/ui/HjIcon';
import { keyPredictors } from './keyPredictors';
import { FeedbackChips, useFeedback } from './useFeedback';
import { RelTag, ScoreBar, StageTag, TrustBlock, VerdictTag, YearChart } from './TopicBits';
import {
  reliabilityOf,
  reportTopN,
  topicDefinition,
  alphaxivPapers,
  termLine,
  topicSnapshot,
  topicTitle,
  trendStatement as statement,
  yearSeries,
  type Reliability,
} from './topicModel';

const WIDE = '(min-width: 1024px)';

function useWide(): boolean {
  const [wide, setWide] = useState(
    () => typeof window !== 'undefined' && window.matchMedia?.(WIDE).matches === true,
  );
  useEffect(() => {
    const query = window.matchMedia?.(WIDE);
    if (!query) return;
    const onChange = (): void => setWide(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return wide;
}

type StageFilter = '' | 'EMBRYONIC' | 'EMERGING' | 'ACCELERATING' | 'MATURING';
const STAGE_CHIPS: ReadonlyArray<[StageFilter, string]> = [
  ['', 'Все стадии'],
  ['EMBRYONIC', 'Зачаточные'],
  ['EMERGING', 'Зарождающиеся'],
  ['ACCELERATING', 'Ускоряющиеся'],
  ['MATURING', 'Зрелые'],
];

export interface TopicRowData {
  trend: RankedTrend;
  reliability: Reliability;
  isNew: boolean;
  previousRank: number | null;
}

export function trendHref(report: TrendReport, trend: RankedTrend): string {
  return `/reports/${report.id}/trends/${encodeURIComponent(trend.trendKey)}`;
}

function Preview({
  report,
  row,
  total,
}: {
  report: TrendReport;
  row: TopicRowData | undefined;
  total: number;
}): React.ReactElement {
  const onVerdict = useFeedback(report);
  const feedbackOn = useFeature('trend-feedback');
  const { toggleBrief } = useBriefing();
  const [briefing] = useLocal<TopicSnapshot[]>(BRIEFING_KEY, []);
  if (!row) {
    return (
      <div className="empty">
        <h3>Тема не выбрана</h3>
        <p className="body-muted">Выберите тему в списке.</p>
      </div>
    );
  }
  const { trend, reliability } = row;
  const title = topicTitle(trend);
  const inBrief = briefing.some((item) => topicId(item) === `${report.id}|${trend.trendKey}`);
  const predictors = keyPredictors(trend);
  return (
    <>
      <div className="od-stack" style={{ '--od-gap': '6px' } as React.CSSProperties}>
        <p className="eyebrow">
          Место {trend.rank} из {total}
          {row.isNew ? ' · новая тема' : row.previousRank ? ` · было ${row.previousRank}` : ''}
        </p>
        {statement(trend) ? (
          <>
            <h2 className="h3">{statement(trend)}</h2>
            <p className="caption">{termLine(trend)}</p>
          </>
        ) : (
          <>
            <h2 className="h2" lang={title.original ? undefined : 'en'}>
              {title.title}
            </h2>
            {title.original ? (
              <p className="caption">
                <span lang="en">{title.original}</span> — оригинальное название
              </p>
            ) : null}
          </>
        )}
      </div>
      <div className="preview__score">
        <span className="big-score num">{Math.round(trend.assessment.score)}</span>
        <span className="big-score-cap">
          <span className="caption">балл зарождения из 100</span>
          <ScoreBar score={trend.assessment.score} />
        </span>
      </div>
      <div className="od-cluster" style={{ '--od-gap': '16px' } as React.CSSProperties}>
        <StageTag stage={trend.lifecycleStage} />
        <RelTag level={reliability.level} />
      </div>
      <section className="od-stack">
        <h3 className="h5">Что это</h3>
        <p className="od-clamp-3">{topicDefinition(trend)}</p>
      </section>
      {predictors.length > 0 ? (
        <section className="od-stack">
          <h3 className="h5">Почему в отчёте</h3>
          <ul className="facts">
            {predictors.map((predictor) => (
              <li key={predictor.indicator}>
                <HjIcon
                  name={
                    predictor.indicator === 'novelty'
                      ? 'clock'
                      : predictor.indicator === 'diffusion'
                        ? 'building'
                        : 'trending'
                  }
                  size={16}
                />
                <span>{predictor.text}</span>
              </li>
            ))}
            <li>
              <HjIcon name="file" size={16} />
              <span>{pluralize(trend.totalDocuments, DOCUMENTS)} в корпусе направления</span>
            </li>
            {alphaxivPapers(trend) > 0 ? (
              <li>
                <HjIcon name="check" size={16} />
                <span>
                  Подтверждена статьями alphaXiv: {alphaxivPapers(trend)} — это поднимает тему в
                  балле
                </span>
              </li>
            ) : null}
          </ul>
        </section>
      ) : null}
      <TrustBlock reliability={reliability} />
      <YearChart
        series={yearSeries(trend, report.generatedAt)}
        firstYear={trend.firstMentionYear}
        compact
      />
      <div className="preview__actions">
        <Link className="btn btn--primary" to={trendHref(report, trend)}>
          Открыть карточку
          <HjIcon name="arrow-right" size={18} />
        </Link>
        <button
          type="button"
          className="btn btn--secondary"
          aria-pressed={inBrief}
          onClick={() => toggleBrief(topicSnapshot(report, trend))}
        >
          <HjIcon name={inBrief ? 'check' : 'bookmark'} size={18} />
          {inBrief ? 'В записке' : 'В записку'}
        </button>
      </div>
      {feedbackOn ? <FeedbackChips trend={trend} onVerdict={onVerdict} idPrefix="pv" /> : null}
    </>
  );
}

export function TopicsPanel({
  report,
  rows,
}: {
  report: TrendReport;
  rows: TopicRowData[];
}): React.ReactElement {
  const wide = useWide();
  const navigate = useNavigate();
  const [stage, setStage] = useState<StageFilter>('');
  const [onlyOk, setOnlyOk] = useState(false);
  const storageKey = `hj:selected:${report.id}`;
  const [selected, setSelected] = useState<string | null>(() => {
    try {
      return window.sessionStorage.getItem(storageKey);
    } catch {
      return null;
    }
  });

  const list = useMemo(
    () =>
      rows.filter(
        (row) =>
          (!stage || row.trend.lifecycleStage === stage) &&
          (!onlyOk || row.reliability.level === 'ok'),
      ),
    [rows, stage, onlyOk],
  );
  const current = list.find((row) => row.trend.trendKey === selected) ?? list[0];

  const select = (trendKey: string, focus = false): void => {
    setSelected(trendKey);
    try {
      window.sessionStorage.setItem(storageKey, trendKey);
    } catch {
      // Выбор не переживёт перезагрузку — не беда.
    }
    if (focus) {
      const row = document.querySelector<HTMLElement>(
        `.topic-row[data-key="${CSS.escape(trendKey)}"]`,
      );
      row?.focus();
      row?.scrollIntoView({ block: 'nearest' });
    }
  };

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (document.querySelector('dialog[open]')) return;
      const target = event.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')
      )
        return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (!['ArrowDown', 'ArrowUp', 'j', 'k'].includes(event.key)) return;
      if (list.length === 0) return;
      const index = current ? list.indexOf(current) : -1;
      const step = event.key === 'ArrowDown' || event.key === 'j' ? 1 : -1;
      const next = list[Math.max(0, Math.min(list.length - 1, index + step))];
      if (!next) return;
      event.preventDefault();
      select(next.trend.trendKey, true);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  const stageCount = (value: StageFilter): number =>
    value ? rows.filter((row) => row.trend.lifecycleStage === value).length : rows.length;
  const okCount = rows.filter((row) => row.reliability.level === 'ok').length;
  const flagged = rows.filter((row) => row.trend.feedback?.verdict === 'NOISE').length;
  const measured = rows.some((row) => row.reliability.stable !== null);
  const stableCount = rows.filter((row) => row.reliability.stable === true).length;
  const topN = reportTopN(report);

  let divider = -1;
  if (measured) {
    for (let index = 1; index < list.length; index += 1) {
      if (list.slice(index).every((row) => row.reliability.stable === false)) {
        divider = index;
        break;
      }
    }
  }

  return (
    <>
      <div className="toolbar">
        <div className="groups">
          <div className="od-cluster" role="group" aria-label="Стадия жизненного цикла">
            {STAGE_CHIPS.filter(([value]) => stageCount(value) > 0).map(([value, label]) => (
              <button
                key={label}
                type="button"
                className="chip"
                aria-pressed={stage === value}
                onClick={() => setStage(value)}
              >
                {label} <span className="n">{stageCount(value)}</span>
              </button>
            ))}
          </div>
          <div className="od-cluster" role="group" aria-label="Надёжность">
            <button
              type="button"
              className="chip"
              aria-pressed={!onlyOk}
              onClick={() => setOnlyOk(false)}
            >
              Любая надёжность <span className="n">{rows.length}</span>
            </button>
            <button
              type="button"
              className="chip"
              aria-pressed={onlyOk}
              onClick={() => setOnlyOk(true)}
            >
              Только надёжные <span className="n">{okCount}</span>
            </button>
          </div>
        </div>
        <p className="kbd-hint">
          <kbd>↑</kbd>
          <kbd>↓</kbd> выбрать · <kbd>Enter</kbd> открыть
        </p>
      </div>

      {flagged > 0 ? (
        <div className="notice mb-6">
          <HjIcon name="eye-off" />
          <div>
            Вы пометили {pluralize(flagged, { one: 'тему', few: 'темы', many: 'тем' })} как «не
            технология». В следующем прогоне их место займут следующие кандидаты.
          </div>
        </div>
      ) : null}

      {list.length === 0 ? (
        <div className="empty">
          <h3>Нет тем с такими условиями</h3>
          <p className="body-muted">
            {onlyOk && okCount === 0
              ? 'У каждой темы этого отчёта есть хотя бы одна оговорка — снимите фильтр «Только надёжные», чтобы увидеть их с причинами.'
              : 'Ни одна тема этого отчёта не подходит под оба фильтра сразу. Снимите один из них.'}
          </p>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => {
              setStage('');
              setOnlyOk(false);
            }}
          >
            Сбросить фильтры
          </button>
        </div>
      ) : (
        <div className="report-grid">
          <div>
            <p className="list-note">
              <HjIcon name="info" size={16} />
              <span>
                Отсортировано по баллу зарождения.
                {measured
                  ? ` ${stableCount} из ${pluralize(rows.length, TOPICS)} держатся в отчёте при любом наборе весов индикаторов.`
                  : ' Устойчивость места к весам для этого отчёта не измерялась.'}
              </span>
            </p>
            <ol className="topic-list stagger" aria-label="Темы отчёта">
              {list.map((row, index) => {
                const title = topicTitle(row.trend);
                const on = current?.trend.trendKey === row.trend.trendKey;
                return (
                  <li key={row.trend.trendKey} style={{ '--i': index } as React.CSSProperties}>
                    {index === divider ? (
                      <div className="tail-divider">
                        <span>
                          Ниже — место зависит от весов индикаторов: при части наборов тема выпадает
                          из ТОП-{topN}
                        </span>
                      </div>
                    ) : null}
                    <Link
                      className={`topic-row${on ? ' is-selected' : ''}${row.trend.feedback?.verdict === 'NOISE' ? ' is-flagged' : ''}`}
                      to={trendHref(report, row.trend)}
                      data-key={row.trend.trendKey}
                      aria-current={on ? 'true' : undefined}
                      onFocus={() => {
                        if (wide) select(row.trend.trendKey);
                      }}
                      onClick={(event) => {
                        // На широком экране щелчок выбирает тему для превью; открыть — Enter или
                        // двойной щелчок. Модификаторы оставляют ссылке её обычное поведение.
                        if (
                          wide &&
                          event.detail > 0 &&
                          !event.metaKey &&
                          !event.ctrlKey &&
                          !event.shiftKey &&
                          event.button === 0
                        ) {
                          event.preventDefault();
                          select(row.trend.trendKey);
                        }
                      }}
                      onDoubleClick={() => navigate(trendHref(report, row.trend))}
                    >
                      <span className="rank" aria-hidden="true">
                        {row.trend.rank}
                      </span>
                      <span className="topic-row__main">
                        {statement(row.trend) ? (
                          <>
                            <span className="topic-row__title">
                              <span className="sr-only">Место {row.trend.rank}. </span>
                              {statement(row.trend)}
                            </span>
                            <span className="topic-row__orig od-truncate">
                              {termLine(row.trend)}
                            </span>
                          </>
                        ) : (
                          <>
                            <span
                              className="topic-row__title"
                              lang={title.original ? undefined : 'en'}
                            >
                              <span className="sr-only">Место {row.trend.rank}. </span>
                              {title.title}
                            </span>
                            {title.original ? (
                              <span className="topic-row__orig od-truncate" lang="en">
                                {title.original}
                              </span>
                            ) : null}
                          </>
                        )}
                        <span className="topic-row__meta">
                          <StageTag stage={row.trend.lifecycleStage} />
                          <RelTag level={row.reliability.level} />
                          {row.isNew ? <span className="tag tag--outline">новая</span> : null}
                          <VerdictTag trend={row.trend} />
                        </span>
                      </span>
                      <span className="topic-row__score">
                        <span className="v" aria-hidden="true">
                          {Math.round(row.trend.assessment.score)}
                        </span>
                        <ScoreBar score={row.trend.assessment.score} />
                        <span className="sr-only">
                          Балл {row.trend.assessment.score.toFixed(1)} из 100.
                        </span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ol>
          </div>
          {wide ? (
            <aside className="preview card" aria-label="Предпросмотр выбранной темы">
              <Preview report={report} row={current} total={rows.length} />
            </aside>
          ) : null}
        </div>
      )}
    </>
  );
}

export function buildRows(
  report: TrendReport,
  delta:
    | {
        entered: { trendKey: string }[];
        stayed: { trendKey: string; rank: number; rankChange?: number | null }[];
        previousReportId?: string;
        unavailableReason?: string;
      }
    | undefined,
): TopicRowData[] {
  const topN = reportTopN(report);
  const comparable = !!delta && !delta.unavailableReason && !!delta.previousReportId;
  const entered = new Set(delta?.entered.map((entry) => entry.trendKey) ?? []);
  const stayed = new Map(delta?.stayed.map((entry) => [entry.trendKey, entry]) ?? []);
  return report.trends.map((trend) => {
    const kept = stayed.get(trend.trendKey);
    return {
      trend,
      reliability: reliabilityOf(trend, topN),
      isNew: comparable && entered.has(trend.trendKey),
      previousRank:
        kept && kept.rankChange !== null && kept.rankChange !== undefined
          ? kept.rank + kept.rankChange
          : null,
    };
  });
}
