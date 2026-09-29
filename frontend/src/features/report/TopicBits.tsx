/**
 * Мелкие знаки темы, из которых собраны строка списка, превью и карточка.
 *
 * Статусы не кодируются цветом: стадия — четыре столбика нарастающей высоты с подписью,
 * надёжность — форма круга с подписью. Так метка читается и в чёрно-белой печати записки, и
 * человеком, не различающим цвета.
 */
import type { RankedTrend } from '@/api/types';
import { clamp } from '@/lib/format';
import { HjIcon } from '@/ui/HjIcon';
import {
  RELIABILITY_LABEL,
  stageInfo,
  type Reliability,
  type ReliabilityLevel,
  type YearPoint,
} from './topicModel';

export function StageTag({ stage }: { stage: string }): React.ReactElement {
  const info = stageInfo(stage);
  return (
    <span className="stage" title={info.hint || undefined}>
      <span className="stage-meter" aria-hidden="true">
        {[1, 2, 3, 4].map((step) => (
          <i key={step} className={step <= info.level ? 'on' : undefined} />
        ))}
      </span>
      <span>{info.label}</span>
    </span>
  );
}

export function RelIcon({ level }: { level: ReliabilityLevel }): React.ReactElement {
  return (
    <svg
      className="rel-icon"
      width="16"
      height="16"
      viewBox="0 0 16 16"
      aria-hidden="true"
      focusable="false"
    >
      {level === 'ok' ? (
        <circle cx="8" cy="8" r="6" fill="currentColor" />
      ) : level === 'check' ? (
        <>
          <circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <path d="M8 2.5a5.5 5.5 0 0 1 0 11z" fill="currentColor" />
        </>
      ) : (
        <circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      )}
    </svg>
  );
}

export function RelTag({ level }: { level: ReliabilityLevel }): React.ReactElement {
  return (
    <span className={`rel rel--${level}`}>
      <RelIcon level={level} />
      <span>{RELIABILITY_LABEL[level]}</span>
    </span>
  );
}

const TRUST_HEAD: Record<ReliabilityLevel, string> = {
  ok: 'Надёжно',
  check: 'Проверьте перед выводами',
  low: 'Мало данных — вывод предварительный',
};

export function TrustBlock({ reliability }: { reliability: Reliability }): React.ReactElement {
  const items = [
    ...reliability.flags.map((text) => ({ icon: 'alert' as const, text })),
    ...reliability.ok.map((text) => ({ icon: 'check' as const, text })),
  ];
  return (
    <section className={`trust trust--${reliability.level}`} aria-label="Надёжность темы">
      <p className="trust__head">
        <RelIcon level={reliability.level} />
        <span>{TRUST_HEAD[reliability.level]}</span>
      </p>
      {items.length > 0 ? (
        <ul>
          {items.map((item) => (
            <li key={item.text}>
              <HjIcon name={item.icon} size={16} />
              <span>{item.text}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

export function ScoreBar({ score }: { score: number }): React.ReactElement {
  return (
    <span className="score-bar" aria-hidden="true">
      <i style={{ width: `${clamp(score, 0, 100)}%` }} />
    </span>
  );
}

const VERDICT_TAG: Record<string, { label: string; dark?: boolean }> = {
  NOISE: { label: 'не технология', dark: true },
  RELEVANT: { label: 'релевантна' },
  ALREADY_KNOWN: { label: 'уже знаем' },
};

export function VerdictTag({ trend }: { trend: RankedTrend }): React.ReactElement | null {
  const verdict = trend.feedback?.verdict;
  const tag = verdict ? VERDICT_TAG[verdict] : undefined;
  if (!tag) return null;
  return (
    <span className={tag.dark ? 'tag tag--dark' : 'tag'}>
      {verdict === 'NOISE' ? <HjIcon name="eye-off" size={14} /> : null}
      {verdict === 'RELEVANT' ? <HjIcon name="check" size={14} /> : null}
      {tag.label}
    </span>
  );
}

/**
 * Документы по годам. Текущий год — штриховкой: он неполный, и сравнивать его высоту с прошлыми
 * значит видеть спад, которого нет. Год первого достоверного упоминания выделен подписью.
 */
export function YearChart({
  series,
  firstYear,
  compact = false,
}: {
  series: readonly YearPoint[];
  firstYear: number;
  compact?: boolean;
}): React.ReactElement | null {
  if (series.length === 0) return null;
  const max = Math.max(1, ...series.map((point) => point.documents));
  const partial = series.find((point) => point.partial);
  const label = `Документов по годам: ${series.map((p) => `${p.year} — ${p.documents}`).join(', ')}.${
    partial ? ` ${partial.year} — неполный год.` : ''
  } Первое достоверное упоминание — ${firstYear}.`;
  return (
    <figure className="chart">
      <div
        className={compact ? 'bars bars--compact' : 'bars'}
        role="img"
        aria-label={label}
        style={{ gridTemplateColumns: `repeat(${series.length}, minmax(0, 1fr))` }}
      >
        {series.map((point) => {
          const height = point.documents ? Math.max(2, (point.documents / max) * 100) : 0;
          const cls = point.documents === 0 ? ' is-zero' : point.partial ? ' is-partial' : '';
          return (
            <div
              key={point.year}
              className={point.year === firstYear ? 'bars__col is-first' : 'bars__col'}
            >
              {compact ? null : <span className="bars__val">{point.documents}</span>}
              <span className="bars__plot">
                <span
                  className={`bars__bar${cls}`}
                  style={{ '--h': `${height.toFixed(1)}%` } as React.CSSProperties}
                />
              </span>
              <span className="bars__yr">
                {compact ? `’${String(point.year).slice(2)}` : point.year}
              </span>
            </div>
          );
        })}
      </div>
      {compact ? (
        <figcaption>
          Документы по годам. Выделен год первого достоверного упоминания
          {partial ? `, ${partial.year} — неполный` : ''}.
        </figcaption>
      ) : (
        <div className="chart-legend" aria-hidden="true">
          <span>
            <i className="swatch" />
            документов за год
          </span>
          {partial ? (
            <span>
              <i className="swatch swatch--hatch" />
              {partial.year} — неполный год
            </span>
          ) : null}
          <span>
            <i className="swatch swatch--first" />
            год первого достоверного упоминания
          </span>
        </div>
      )}
    </figure>
  );
}

/** Место темы при разных наборах весов: диапазон, текущее место и черта ТОП-N. */
export function RangeViz({
  best,
  worst,
  rank,
  topN,
}: {
  best: number;
  worst: number;
  rank: number;
  topN: number;
}): React.ReactElement {
  const scale = Math.max(topN + 5, worst + 2);
  const out = scale - topN;
  const cells: React.ReactElement[] = [];
  const axis: React.ReactElement[] = [];
  for (let place = 1; place <= scale; place += 1) {
    if (place === topN + 1) {
      cells.push(<span key="edge" className="rr__edge" />);
      axis.push(<span key="edge" />);
    }
    const cls = ['rr__c'];
    if (place > topN) cls.push('is-out');
    if (place >= best && place <= worst) cls.push('is-in');
    if (place === rank) cls.push('is-cur');
    cells.push(<span key={place} className={cls.join(' ')} />);
    axis.push(<span key={place}>{place === 1 || place % 5 === 0 ? place : ''}</span>);
  }
  const style = { '--out': out, '--topn': topN } as React.CSSProperties;
  return (
    <>
      <div
        className="range-viz"
        role="img"
        aria-label={`Место темы при разных наборах весов: от ${best} до ${worst}; текущее место ${rank}; граница отчёта — ${topN}.`}
      >
        <div className="rr" style={style} aria-hidden="true">
          {cells}
        </div>
        <div className="rr-axis" style={style} aria-hidden="true">
          {axis}
        </div>
      </div>
      <div className="rr-legend" aria-hidden="true">
        <span>
          <i className="sw sw--in" />
          диапазон места
        </span>
        <span>
          <i className="sw sw--cur" />
          текущее место
        </span>
        <span>
          <i className="sw sw--out" />
          за чертой ТОП-{topN}
        </span>
      </div>
    </>
  );
}

/** Скелетон-строка: место под содержимое, пока оно грузится. */
export function SkelLine({ width, height }: { width: string; height: number }): React.ReactElement {
  return <span className="skel" style={{ width, height }} />;
}
