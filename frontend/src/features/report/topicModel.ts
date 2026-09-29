/**
 * Тема отчёта в тех величинах, из которых собран экран Hiddenjem.
 *
 * Всё выводится из ответа API — ни одного числа, которого движок не прислал. Где величины нет
 * (отчёт выпущен до её появления, движок её не считает), функция так и говорит — `null`, — а
 * интерфейс показывает «не измерялось», а не ноль и не выдуманное значение.
 */
import type { Evidence, LifecycleStage, RankedTrend, TrendReport } from '@/api/types';
import { LOW_EVIDENCE_CONFIDENCE_THRESHOLD } from '@/api/types';
import { formatPercent, pluralize } from '@/lib/format';
import { translate } from '@/lib/i18n/translate';
import type { TopicSnapshot } from '@/lib/localState';
import { verdictFor } from './rankStability';

/* ── Названия ───────────────────────────────────────────────── */

/** Направление так, как его написали, — с заглавной буквы. */
export function directionTitle(query: string): string {
  const trimmed = query.trim();
  return trimmed ? trimmed.charAt(0).toLocaleUpperCase('ru-RU') + trimmed.slice(1) : trimmed;
}

/** Сравнение формулировок направления: регистр, «ё» и лишние пробелы не делают вопрос другим. */
export function normalizeQuery(query: string): string {
  return query.toLocaleLowerCase('ru-RU').replace(/ё/g, 'е').replace(/\s+/g, ' ').trim();
}

export interface TopicTitle {
  /** Что показать крупно: русский слой, если он есть, иначе оригинал. */
  title: string;
  /** Оригинальное название — только когда крупно показан перевод. */
  original: string | null;
}

export function topicTitle(trend: RankedTrend): TopicTitle {
  const ru = trend.localization?.title?.trim();
  return ru && ru !== trend.title
    ? { title: ru, original: trend.title }
    : { title: trend.title, original: null };
}

/**
 * Тема, сформулированная как тренд, — одно предложение о том, что меняется; `null`, если отчёт его
 * не несёт. Термин отвечает на вопрос «о чём», предложение — «что происходит»: читатель без
 * подготовки видит в словосочетании тему, а не тренд.
 */
export function trendStatement(trend: RankedTrend): string | null {
  return trend.localization?.statement?.trim() || null;
}

/** Термин одной строкой: русское название и оригинал рядом, если перевод есть. */
export function termLine(trend: RankedTrend): string {
  const { title, original } = topicTitle(trend);
  return original ? `${title} · ${original}` : title;
}

export function topicDefinition(trend: RankedTrend): string {
  return trend.localization?.definition?.trim() || trend.definition;
}

/** Русское название документа, если русский слой его нёс. */
export function evidenceTitleRu(trend: RankedTrend, index: number): string | null {
  const ru = trend.localization?.evidenceTitles?.[index]?.trim();
  return ru && ru !== trend.evidence[index]?.title ? ru : null;
}

/* ── Стадия ─────────────────────────────────────────────────── */

const STAGE_LEVEL: Record<LifecycleStage, number> = {
  EMBRYONIC: 1,
  EMERGING: 2,
  ACCELERATING: 3,
  MATURING: 4,
};

export interface StageInfo {
  level: number;
  label: string;
  hint: string;
}

export function stageInfo(stage: string): StageInfo {
  const known = stage in STAGE_LEVEL;
  return {
    level: known ? STAGE_LEVEL[stage as LifecycleStage] : 0,
    label: known ? translate(`lifecycle.${stage as LifecycleStage}`) : stage,
    hint: known ? translate(`lifecycleHint.${stage as LifecycleStage}`) : '',
  };
}

/* ── Надёжность ─────────────────────────────────────────────── */

export type ReliabilityLevel = 'ok' | 'check' | 'low';

export const RELIABILITY_LABEL: Record<ReliabilityLevel, string> = {
  ok: 'Надёжно',
  check: 'Проверьте',
  low: 'Мало данных',
};

export interface Reliability {
  level: ReliabilityLevel;
  /** Оговорки — то, что стоит проверить до выводов. */
  flags: string[];
  /** Что подтверждено. */
  ok: string[];
  /** Держится ли тема в отчёте при любых весах; `null` — устойчивость не измерялась. */
  stable: boolean | null;
  /** Доля документов темы, отнесённых к направлению; `null` — не измерялась. */
  share: number | null;
}

/** Ниже этой доли тема, возможно, пришла из соседнего направления. У настоящих тем — около ⅔. */
export const DIRECTION_SHARE_FLOOR = 0.5;

/**
 * Одна метка вместо четырёх оговорок — и все оговорки рядом с ней.
 *
 * Метка собирает то, что уже есть в ответе: диапазон места при разных весах, долю направления,
 * уверенность и признак малой доказательной базы, доверенность источников и пометку аналитика.
 * Не измеренное не превращается ни в оговорку, ни в подтверждение: ложная оговорка обесценивает
 * настоящие, а ложное подтверждение хуже её.
 */
export function reliabilityOf(trend: RankedTrend, topN: number): Reliability {
  const flags: string[] = [];
  const ok: string[] = [];
  const range = trend.assessment.rankStability;
  const verdict = verdictFor(range, topN);
  const stable = verdict === null ? null : verdict !== 'slips';
  if (range && verdict) {
    const span = range.best === range.worst ? String(range.best) : `${range.best}–${range.worst}`;
    if (verdict === 'slips') {
      flags.push(
        `Место зависит от весов: ${span} — при части наборов тема выпадает из ТОП-${topN}`,
      );
    } else {
      ok.push(`Держится в отчёте при любом наборе весов: место ${span}`);
    }
  }

  const share = trend.directionShare ?? null;
  if (share !== null) {
    const documents = pluralize(trend.totalDocuments, {
      one: 'документа',
      few: 'документов',
      many: 'документов',
    });
    const inDirection = Math.round(share * trend.totalDocuments);
    if (share < DIRECTION_SHARE_FLOOR) {
      flags.push(
        `К направлению отнесены ${inDirection} из ${documents} темы — возможно, она из соседнего направления`,
      );
    } else {
      ok.push(`К направлению отнесены ${inDirection} из ${documents} темы`);
    }
  }

  if (trend.lowCredibilityOnly) {
    flags.push('Все источники темы — пониженной доверенности: отраслевые медиа и пресс-релизы');
  }
  if (trend.feedback?.verdict === 'NOISE') {
    flags.push('Вы пометили тему как «не технология»');
  }

  const confidence = trend.assessment.confidence;
  const low = trend.assessment.lowEvidence || confidence < LOW_EVIDENCE_CONFIDENCE_THRESHOLD;
  if (low) {
    flags.unshift(
      `Мало данных: ${pluralize(trend.totalDocuments, { one: 'документ', few: 'документа', many: 'документов' })}, уверенность ${formatPercent(confidence)}`,
    );
  } else {
    ok.push(`Уверенность модели ${formatPercent(confidence)}`);
  }

  return { level: low ? 'low' : flags.length > 0 ? 'check' : 'ok', flags, ok, stable, share };
}

/* ── Ряды по годам ──────────────────────────────────────────── */

export interface YearPoint {
  year: number;
  documents: number;
  /** Год ещё идёт: столбик неполный, и сравнивать его с прошлыми нельзя. */
  partial: boolean;
}

/**
 * Документы темы по календарным годам.
 *
 * Период приходит строкой: год или год с кварталом. Кварталы сводятся к году — на карточке темы
 * читают, как тема росла из года в год, а не поквартальный шум.
 */
export function yearSeries(trend: RankedTrend, generatedAt: string): YearPoint[] {
  const currentYear = new Date(generatedAt).getUTCFullYear();
  const byYear = new Map<number, number>();
  for (const point of trend.timeline) {
    const year = Number.parseInt(point.period.slice(0, 4), 10);
    if (!Number.isFinite(year)) continue;
    byYear.set(year, (byYear.get(year) ?? 0) + point.documentCount);
  }
  return [...byYear.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([year, documents]) => ({ year, documents, partial: year >= currentYear }));
}

/** Рост за последний полный год: во сколько раз больше документов, чем годом раньше. */
export function lastYearGrowth(
  series: readonly YearPoint[],
): { from: YearPoint; to: YearPoint; factor: number } | null {
  const full = series.filter((point) => !point.partial);
  const to = full[full.length - 1];
  const from = full[full.length - 2];
  if (!to || !from || from.documents <= 0) return null;
  return { from, to, factor: to.documents / from.documents };
}

/* ── Диагностика индикаторов ───────────────────────────────── */

export function diagnosticNumber(
  trend: RankedTrend,
  indicator: string,
  key: string,
): number | null {
  const value = trend.assessment.indicators.find((item) => item.name === indicator)?.diagnostics?.[
    key
  ];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Число организаций темы — из диагностики диффузии; нет её — `null`, а не число документов. */
export function organizationCount(trend: RankedTrend): number | null {
  return diagnosticNumber(trend, 'diffusion', 'orgCount');
}

/** Сколько ключевых документов темы пришли из alphaXiv — они поднимают тему в балле. */
export function alphaxivPapers(trend: RankedTrend): number {
  return trend.evidence.filter((evidence) => evidence.sourceId === 'alphaxiv').length;
}

/* ── Источники ──────────────────────────────────────────────── */

const SOURCE_NAMES: Record<string, string> = {
  alphaxiv: 'alphaXiv',
  arxiv: 'arXiv',
  crossref: 'Crossref',
  deepresearch: 'Глубокое исследование',
  webcorpus: 'Веб-корпус',
  europepmc: 'Europe PMC',
  github: 'GitHub',
  habr: 'Хабр',
  hackernews: 'Hacker News',
  industry: 'Отраслевые медиа',
  openalex: 'OpenAlex',
  semanticscholar: 'Semantic Scholar',
  sbir: 'SBIR/STTR',
  uspto: 'USPTO',
  nsf: 'NSF',
  regulators: 'Регуляторы и центробанки',
  edgar: 'SEC EDGAR',
  ietf: 'IETF',
  globenewswire: 'GlobeNewswire',
  prnewswire: 'PR Newswire',
  producthunt: 'Product Hunt',
  patents: 'Патенты',
  lens: 'The Lens',
  lenspatents: 'The Lens: патенты',
  rss: 'RSS-ленты',
  pubmed: 'PubMed',
};

/** Имя источника для читателя. Незнакомый показывается как есть — умолчать о нём было бы хуже. */
export function sourceName(sourceId: string): string {
  return SOURCE_NAMES[sourceId.toLowerCase()] ?? sourceId;
}

export interface SourceCoverage {
  used: string[];
  unavailable: string[];
  total: number;
}

export function sourceCoverage(report: TrendReport): SourceCoverage {
  const used = report.coverage.sourcesUsed;
  const unavailable = report.coverage.unavailableSources ?? [];
  return { used, unavailable, total: used.length + unavailable.length };
}

export function reportTopN(report: TrendReport): number {
  return Math.max(report.trends.length, 1);
}

/* ── Снимок для записки ─────────────────────────────────────── */

function sourceOf(evidence: Evidence): TopicSnapshot['sources'][number] {
  return {
    title: evidence.title,
    url: evidence.url,
    ...(evidence.organization ? { organization: evidence.organization } : {}),
    ...(evidence.publishedOn ? { publishedOn: evidence.publishedOn } : {}),
  };
}

export function topicSnapshot(report: TrendReport, trend: RankedTrend): TopicSnapshot {
  const { title } = topicTitle(trend);
  const reliability = reliabilityOf(trend, reportTopN(report));
  const problem = trend.localization?.problem?.trim() || trend.motivation.problem;
  const benefit = trend.localization?.benefit?.trim() || trend.motivation.benefit;
  return {
    reportId: report.id,
    trendKey: trend.trendKey,
    title,
    original: trend.title,
    ...(trendStatement(trend) ? { statement: trendStatement(trend) ?? undefined } : {}),
    query: report.query,
    rank: trend.rank,
    score: trend.assessment.score,
    stage: stageInfo(trend.lifecycleStage).label,
    reliability: reliability.level,
    flags: reliability.flags,
    definition: topicDefinition(trend),
    ...(problem ? { problem } : {}),
    ...(benefit ? { benefit } : {}),
    sources: trend.evidence.slice(0, 5).map(sourceOf),
    documents: trend.totalDocuments,
    savedAt: new Date().toISOString(),
  };
}
