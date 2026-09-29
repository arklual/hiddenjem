import { describe, expect, it } from 'vitest';
import type { IndicatorScore, RankedTrend } from '@/api/types';
import { KEY_PREDICTOR_LIMIT, keyPredictors } from './keyPredictors';

/**
 * ТЗ требует в интерфейсе «ключевые предикторы: объяснение отнесения технологии к зарождающемуся
 * тренду». Проверяется то, что делает их объяснением, а не украшением: числа берутся из
 * диагностики движка, отсутствие данных не превращается в выдуманную фразу.
 */

function indicator(
  name: IndicatorScore['name'],
  value: number,
  diagnostics: Record<string, unknown>,
  shortfallShare = 0,
): IndicatorScore {
  return { name, value, weight: 0.2, multiplier: 1, shortfallShare, diagnostics };
}

function trend(indicators: IndicatorScore[]): RankedTrend {
  return {
    rank: 1,
    trendKey: 'llm security',
    title: 'LLM security',
    firstMentionYear: 2025,
    totalDocuments: 9,
    assessment: { score: 50, confidence: 0.8, indicators },
    evidence: [],
    timeline: [],
  } as unknown as RankedTrend;
}

describe('ключевые предикторы', () => {
  it('говорят числами из диагностики, а не оценками', () => {
    const predictors = keyPredictors(
      trend([
        indicator('growth', 0.9, { periodFactor: 2.4, burst_startPeriod: '2025' }),
        indicator('diffusion', 0.6, { orgCount: 5, sourceClassCount: 3 }),
      ]),
    );

    expect(predictors.map((item) => item.text)).toEqual([
      '×2,4 публикаций за год, всплеск с 2025',
      '5\u00A0организаций, 3\u00A0типа источников',
    ]);
  });

  it('называют рост относительным, когда движок считал его относительно направления', () => {
    // ×3 на корпусе, который сам вырос втрое, — не находка, и подпись обязана это различать.
    const [growth] = keyPredictors(
      trend([indicator('growth', 0.9, { periodFactor: 3, relativeToCorpus: true })]),
    );

    expect(growth?.text).toBe('доля в направлении ×3 за год');
  });

  it('молчат там, где чисел нет', () => {
    const predictors = keyPredictors(
      trend([indicator('growth', 0.9, {}), indicator('diffusion', 0.8, { orgCount: 1 })]),
    );

    expect(predictors).toEqual([]);
  });

  it('упорядочены от сильнейшего индикатора и не длиннее строки', () => {
    const predictors = keyPredictors(
      trend([
        indicator('novelty', 0.5, { firstYear: 2024 }),
        indicator('growth', 0.9, { periodFactor: 2 }),
        indicator('diffusion', 0.7, { orgCount: 3 }),
        indicator('weakness', 0.6, { recentDocuments: 9, recentMaxObserved: 357, window: 2 }),
      ]),
    );

    expect(predictors).toHaveLength(KEY_PREDICTOR_LIMIT);
    expect(predictors.map((item) => item.indicator)).toEqual(['growth', 'diffusion', 'weakness']);
    expect(predictors[2]?.text).toBe('9\u00A0работ за 2\u00A0г. против 357 у самой частой темы направления');
  });
});
