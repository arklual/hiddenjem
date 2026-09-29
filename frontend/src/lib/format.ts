/**
 * Locale-aware formatting. `ru-RU` by default (non-breaking-space thousands
 * separator, comma decimal mark, `дд.мм.гггг` dates).
 *
 * Timestamps arrive as UTC instants and are rendered in the viewer's own time
 * zone (NFR-D4). Every formatter tolerates malformed input by returning a dash
 * rather than throwing — a bad date must never blank a whole report.
 */
import { getIntlLocale, getLocale } from './i18n/locale';

export const EM_DASH = '—';

/* ── Numbers ─────────────────────────────────────────────────── */

const numberFormatters = new Map<string, Intl.NumberFormat>();

function numberFormat(options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const key = `${getIntlLocale()}|${JSON.stringify(options)}`;
  let formatter = numberFormatters.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(getIntlLocale(), options);
    numberFormatters.set(key, formatter);
  }
  return formatter;
}

export function clamp01(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

export function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, value));
}

/** Integer counts: `1 234`. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return numberFormat({ maximumFractionDigits: 0 }).format(value);
}

export function formatNumber(value: number | null | undefined, fractionDigits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return numberFormat({
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(value);
}

/**
 * Emergence Score, 0–100.
 *
 * One decimal is the honest resolution for a display figure: the stored value is
 * `numeric(9,6)` and is rounded to six decimals before sorting (methodology §9),
 * so showing more here would imply precision the ranking itself does not use.
 * A score of exactly 0 is meaningful (BRULE-4) and is shown as `0,0`, never blank.
 */
export function formatScore(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return formatNumber(clamp(value, 0, 100), 1);
}

/** Indicator `value`, `weight` and `multiplier` — all 0..1, shown at 3 decimals. */
export function formatUnitValue(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return formatNumber(clamp01(value), 3);
}

/** `0.62` → `62 %` (ru-RU uses a non-breaking space before the sign). */
export function formatPercent(value: number | null | undefined, fractionDigits = 0): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return numberFormat({
    style: 'percent',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(clamp01(value));
}

/** `×0,617` — how an indicator scaled the score (methodology §4). */
export function formatMultiplier(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return `×${formatNumber(clamp01(value), 3)}`;
}

/* ── Dates ───────────────────────────────────────────────────── */

function toDate(input: string | number | Date | null | undefined): Date | null {
  if (input === null || input === undefined || input === '') return null;
  const date = input instanceof Date ? input : new Date(input);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** `05.08.2026` */
export function formatDate(input: string | null | undefined): string {
  const date = toDate(input);
  if (!date) return EM_DASH;
  return new Intl.DateTimeFormat(getIntlLocale(), {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(date);
}

/** `5 августа 2026 г.` */
export function formatDateLong(input: string | null | undefined): string {
  const date = toDate(input);
  if (!date) return EM_DASH;
  return new Intl.DateTimeFormat(getIntlLocale(), {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(date);
}

/** `05.08.2026, 21:47` — in the viewer's time zone. */
export function formatDateTime(input: string | null | undefined): string {
  const date = toDate(input);
  if (!date) return EM_DASH;
  return new Intl.DateTimeFormat(getIntlLocale(), {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/** Machine-readable value for a `<time datetime>` attribute. */
export function toDateTimeAttr(input: string | null | undefined): string | undefined {
  const date = toDate(input);
  return date ? date.toISOString() : undefined;
}

type RelativeStep = [limitSeconds: number, divisor: number, unit: Intl.RelativeTimeFormatUnit];

const YEAR_STEP: RelativeStep = [Number.POSITIVE_INFINITY, 31536000, 'year'];

const RELATIVE_STEPS: readonly RelativeStep[] = [
  [60, 1, 'second'],
  [3600, 60, 'minute'],
  [86400, 3600, 'hour'],
  [2592000, 86400, 'day'],
  [31536000, 2592000, 'month'],
  YEAR_STEP,
];

/** `3 минуты назад` */
export function formatRelativeTime(
  input: string | null | undefined,
  now: number = Date.now(),
): string {
  const date = toDate(input);
  if (!date) return EM_DASH;
  const deltaSeconds = (date.getTime() - now) / 1000;
  const absolute = Math.abs(deltaSeconds);
  const step = RELATIVE_STEPS.find(([limit]) => absolute < limit) ?? YEAR_STEP;
  const formatter = new Intl.RelativeTimeFormat(getIntlLocale(), { numeric: 'auto' });
  return formatter.format(Math.round(deltaSeconds / step[1]), step[2]);
}

/* ── Durations ───────────────────────────────────────────────── */

/** `1 мин 23 с` / `47 с` — elapsed and ETA on the progress screen. */
export function formatDuration(totalSeconds: number | null | undefined): string {
  if (totalSeconds === null || totalSeconds === undefined || !Number.isFinite(totalSeconds)) {
    return EM_DASH;
  }
  const seconds = Math.max(0, Math.round(totalSeconds));
  const isRu = getLocale() === 'ru';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;

  const units = isRu ? { h: 'ч', m: 'мин', s: 'с' } : { h: 'h', m: 'min', s: 's' };
  const parts: string[] = [];
  if (h > 0) parts.push(`${h} ${units.h}`);
  if (m > 0) parts.push(`${m} ${units.m}`);
  if (h === 0 && (s > 0 || parts.length === 0)) parts.push(`${s} ${units.s}`);
  return parts.join(' ');
}

/** `MM:SS` stopwatch for the progress header. */
export function formatStopwatch(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds));
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/* ── Plurals ─────────────────────────────────────────────────── */

const pluralRules = new Map<string, Intl.PluralRules>();

/**
 * Russian needs three forms (`1 документ`, `2 документа`, `5 документов`);
 * `Intl.PluralRules` picks the category so the caller only supplies the words.
 */
export function plural(count: number, forms: { one: string; few: string; many: string }): string {
  const locale = getIntlLocale();
  let rules = pluralRules.get(locale);
  if (!rules) {
    rules = new Intl.PluralRules(locale);
    pluralRules.set(locale, rules);
  }
  const category = rules.select(count);
  if (category === 'one') return forms.one;
  if (category === 'few') return forms.few;
  return forms.many;
}

export function pluralize(
  count: number,
  forms: { one: string; few: string; many: string },
): string {
  return `${formatCount(count)}\u00A0${plural(count, forms)}`;
}

/* ── Misc ────────────────────────────────────────────────────── */

/** ISO-3166 alpha-2 → `🇩🇪` flag, for evidence organisations. */
export function countryFlag(code: string | null | undefined): string | null {
  if (!code || code.length !== 2 || !/^[A-Za-z]{2}$/.test(code)) return null;
  const base = 0x1f1e6;
  const upper = code.toUpperCase();
  // Two chars, both validated above.
  const first = upper.charCodeAt(0) - 65;
  const second = upper.charCodeAt(1) - 65;
  return String.fromCodePoint(base + first, base + second);
}

/** Hostname of an evidence URL, used as a compact source label. */
export function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

export function truncate(text: string, maxLength: number): string {
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1).trimEnd()}…`;
}
