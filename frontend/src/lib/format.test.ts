import { describe, expect, it } from 'vitest';
import {
  clamp01,
  countryFlag,
  EM_DASH,
  formatCount,
  formatDate,
  formatDuration,
  formatMultiplier,
  formatPercent,
  formatScore,
  formatStopwatch,
  formatUnitValue,
  hostnameOf,
  plural,
  pluralize,
  truncate,
} from './format';

/**
 * ICU uses different space characters for digit grouping across versions
 * (U+00A0 vs U+202F), so assertions normalise whitespace rather than hardcoding
 * one of them — otherwise these tests break on a Node upgrade for no real reason.
 */
function ns(value: string): string {
  return value.replace(/[\s\u00a0\u202f]/g, ' ');
}

describe('formatScore', () => {
  it('renders one decimal with a ru-RU decimal comma', () => {
    expect(formatScore(72.4567)).toBe('72,5');
    expect(formatScore(100)).toBe('100,0');
  });

  it('shows an annihilated score as a real zero, never as a blank', () => {
    // BRULE-4: a zero is a meaningful outcome, not missing data.
    expect(formatScore(0)).toBe('0,0');
  });

  it('clamps out-of-range values instead of printing them', () => {
    expect(formatScore(-3)).toBe('0,0');
    expect(formatScore(101.2)).toBe('100,0');
  });

  it('falls back to a dash for absent or non-finite input', () => {
    expect(formatScore(null)).toBe(EM_DASH);
    expect(formatScore(undefined)).toBe(EM_DASH);
    expect(formatScore(Number.NaN)).toBe(EM_DASH);
  });
});

describe('indicator formatting', () => {
  it('shows indicator values at three decimals', () => {
    expect(formatUnitValue(0.6172)).toBe('0,617');
    expect(formatUnitValue(1)).toBe('1,000');
    expect(formatUnitValue(0)).toBe('0,000');
  });

  it('prefixes a multiplier with the multiplication sign', () => {
    expect(formatMultiplier(0.8641)).toBe('×0,864');
    expect(formatMultiplier(1)).toBe('×1,000');
  });

  it('clamps indicator values into the contract range 0..1', () => {
    // A numeric(9,6) round-trip can emit 1.0000001; that must not render as >1.
    expect(formatUnitValue(1.0000001)).toBe('1,000');
    expect(formatUnitValue(-0.2)).toBe('0,000');
  });

  it('formats shortfall shares as percentages', () => {
    expect(ns(formatPercent(0.34))).toBe('34 %');
    expect(ns(formatPercent(0.3456, 1))).toBe('34,6 %');
  });
});

describe('formatCount', () => {
  it('groups thousands', () => {
    expect(ns(formatCount(1234567))).toBe('1 234 567');
  });

  it('renders zero and dashes absent values', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(null)).toBe(EM_DASH);
    expect(formatCount(undefined)).toBe(EM_DASH);
  });
});

describe('formatDate', () => {
  it('renders dd.mm.yyyy', () => {
    expect(formatDate('2026-08-05')).toBe('05.08.2026');
  });

  it('never throws on malformed input', () => {
    expect(formatDate('не дата')).toBe(EM_DASH);
    expect(formatDate('')).toBe(EM_DASH);
    expect(formatDate(null)).toBe(EM_DASH);
  });
});

describe('durations', () => {
  it('renders elapsed and ETA in Russian units', () => {
    expect(formatDuration(45)).toBe('45 с');
    expect(formatDuration(83)).toBe('1 мин 23 с');
    expect(formatDuration(3661)).toBe('1 ч 1 мин');
    expect(formatDuration(0)).toBe('0 с');
  });

  it('renders a stopwatch as MM:SS', () => {
    expect(formatStopwatch(0)).toBe('00:00');
    expect(formatStopwatch(83)).toBe('01:23');
    expect(formatStopwatch(600)).toBe('10:00');
  });
});

describe('plural', () => {
  const forms = { one: 'документ', few: 'документа', many: 'документов' };

  it('picks the right Russian form for each category', () => {
    expect(plural(1, forms)).toBe('документ');
    expect(plural(2, forms)).toBe('документа');
    expect(plural(5, forms)).toBe('документов');
    expect(plural(11, forms)).toBe('документов');
    expect(plural(21, forms)).toBe('документ');
    expect(plural(0, forms)).toBe('документов');
  });

  it('combines the count with the chosen form', () => {
    expect(ns(pluralize(1234, forms))).toBe('1 234 документа');
  });
});

describe('misc helpers', () => {
  it('clamps to the unit interval', () => {
    expect(clamp01(0.5)).toBe(0.5);
    expect(clamp01(2)).toBe(1);
    expect(clamp01(-1)).toBe(0);
    expect(clamp01(Number.NaN)).toBe(0);
  });

  it('derives a flag from a valid alpha-2 code only', () => {
    expect(countryFlag('DE')).toBe('🇩🇪');
    expect(countryFlag('de')).toBe('🇩🇪');
    expect(countryFlag('DEU')).toBeNull();
    expect(countryFlag('')).toBeNull();
    expect(countryFlag(null)).toBeNull();
  });

  it('extracts a hostname and tolerates a non-URL', () => {
    expect(hostnameOf('https://www.nature.com/articles/x')).toBe('nature.com');
    expect(hostnameOf('not a url')).toBe('not a url');
  });

  it('truncates with an ellipsis only when needed', () => {
    expect(truncate('короткий', 20)).toBe('короткий');
    expect(truncate('очень длинное название', 10)).toBe('очень дли…');
    // The cut must not leave a dangling space before the ellipsis.
    expect(truncate('очень длинное', 7)).toBe('очень…');
  });
});
