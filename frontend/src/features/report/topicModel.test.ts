import { describe, expect, it } from 'vitest';
import type { RankedTrend, ResearchRequestView } from '@/api/types';
import { latestReports, readyReportFor } from '@/api/queries';
import { briefingMarkdown } from '@/app/briefing';
import type { TopicSnapshot } from '@/lib/localState';
import {
  directionTitle,
  lastYearGrowth,
  reliabilityOf,
  termLine,
  topicTitle,
  trendStatement,
  yearSeries,
} from './topicModel';

function trend(
  patch: Omit<Partial<RankedTrend>, 'assessment'> & {
    assessment?: Partial<RankedTrend['assessment']>;
  },
): RankedTrend {
  const { assessment, ...rest } = patch;
  return {
    rank: 1,
    trendKey: 'canary token',
    title: 'canary tokens',
    definition: 'def',
    motivation: { problem: '', benefit: '' },
    lifecycleStage: 'EMERGING',
    firstMentionYear: 2023,
    totalDocuments: 40,
    timeline: [],
    evidence: [],
    ...rest,
    assessment: { score: 47, confidence: 0.8, lowEvidence: false, indicators: [], ...assessment },
  };
}

describe('метка надёжности', () => {
  it('«надёжно», когда оговорок нет', () => {
    expect(reliabilityOf(trend({ directionShare: 0.9 }), 15).level).toBe('ok');
  });

  it('«проверьте», когда место выпадает из ТОП при части весов', () => {
    const result = reliabilityOf(
      trend({ assessment: { rankStability: { best: 9, worst: 19 } } }),
      15,
    );
    expect(result.level).toBe('check');
    expect(result.stable).toBe(false);
    expect(result.flags[0]).toContain('9–19');
  });

  it('«проверьте», когда тема, возможно, из соседнего направления', () => {
    const result = reliabilityOf(trend({ directionShare: 0.2 }), 15);
    expect(result.level).toBe('check');
    expect(result.flags.join()).toContain('соседнего направления');
  });

  it('«мало данных» перекрывает прочие оговорки', () => {
    const result = reliabilityOf(
      trend({ directionShare: 0.2, assessment: { lowEvidence: true } }),
      15,
    );
    expect(result.level).toBe('low');
    expect(result.flags[0]).toMatch(/^Мало данных/);
  });

  it('не измеренное не становится ни оговоркой, ни подтверждением', () => {
    // Второй движок устойчивость к весам не считает: без диапазона нет и слов о весах.
    const result = reliabilityOf(trend({}), 15);
    expect(result.stable).toBeNull();
    expect(result.share).toBeNull();
    expect([...result.flags, ...result.ok].join()).not.toContain('вес');
  });
});

describe('ряд по годам', () => {
  const series = yearSeries(
    trend({
      timeline: [
        { period: '2023', documentCount: 4 },
        { period: '2024', documentCount: 39 },
        { period: '2025-Q1', documentCount: 20 },
        { period: '2025-Q2', documentCount: 24 },
        { period: '2026', documentCount: 12 },
      ],
    }),
    '2026-09-27T14:13:09Z',
  );

  it('сводит кварталы к году и помечает текущий год неполным', () => {
    expect(series.map((point) => [point.year, point.documents, point.partial])).toEqual([
      [2023, 4, false],
      [2024, 39, false],
      [2025, 44, false],
      [2026, 12, true],
    ]);
  });

  it('считает рост по последнему полному году, а не по неполному', () => {
    const growth = lastYearGrowth(series);
    expect(growth?.to.year).toBe(2025);
    expect(growth?.factor).toBeCloseTo(44 / 39);
  });
});

describe('названия', () => {
  it('показывает русский слой крупно, а оригинал — рядом', () => {
    expect(topicTitle(trend({ localization: { title: 'канареечные токены' } }))).toEqual({
      title: 'канареечные токены',
      original: 'canary tokens',
    });
    expect(topicTitle(trend({}))).toEqual({ title: 'canary tokens', original: null });
  });

  it('пишет направление с заглавной', () => {
    expect(directionTitle('финтех')).toBe('Финтех');
  });
});

function request(
  id: string,
  query: string,
  status: ResearchRequestView['status'] = 'COMPLETED',
): ResearchRequestView {
  return {
    id,
    query,
    normalizedQuery: query.toLowerCase(),
    parameters: {},
    status,
    progress: { stage: 'DONE', percent: 100 },
    reportId: status === 'COMPLETED' ? `r-${id}` : null,
    submittedAt: '2026-09-27T10:00:00Z',
  };
}

describe('готовые отчёты', () => {
  const list = [
    request('3', 'Финтех', 'ANALYZING'),
    request('2', 'финтех'),
    request('1', 'финтех'),
    request('0', 'робототехника'),
  ];

  it('берёт по направлению самый свежий готовый отчёт', () => {
    expect(latestReports(list).map((item) => item.id)).toEqual(['2', '0']);
  });

  it('находит готовый отчёт по формулировке независимо от регистра и пробелов', () => {
    expect(readyReportFor(list, '  ФИНТЕХ ')?.id).toBe('2');
    expect(readyReportFor(list, 'квантовые вычисления')).toBeUndefined();
  });
});

describe('записка для комитета', () => {
  it('несёт каждую оговорку и ссылку на источник', () => {
    const item: TopicSnapshot = {
      reportId: 'r',
      trendKey: 'k',
      title: 'канареечные токены',
      original: 'canary tokens',
      query: 'защита искусственного интеллекта',
      rank: 2,
      score: 46.67,
      stage: 'Ускоряющаяся',
      reliability: 'check',
      flags: ['К направлению отнесены 5 из 40 документов темы'],
      definition: 'Ловушки-маркеры',
      sources: [{ title: 'Paper', url: 'https://arxiv.org/abs/1', organization: 'MIT' }],
      documents: 40,
      savedAt: '2026-09-28T00:00:00Z',
    };
    const text = briefingMarkdown([item], new Date('2026-09-28'));
    expect(text).toContain('## 1. канареечные токены (canary tokens)');
    expect(text).toContain('  - К направлению отнесены 5 из 40 документов темы');
    expect(text).toContain('https://arxiv.org/abs/1');
  });
});

describe('тема как тренд', () => {
  it('отдаёт предложение, когда отчёт его несёт, и молчит, когда нет', () => {
    const withStatement = trend({
      localization: {
        title: 'канареечные токены',
        statement: 'Защитники начинают расставлять приманки, которые выдают взломщика.',
      },
    });
    expect(trendStatement(withStatement)).toBe(
      'Защитники начинают расставлять приманки, которые выдают взломщика.',
    );
    expect(termLine(withStatement)).toBe('канареечные токены · canary tokens');
    expect(trendStatement(trend({}))).toBeNull();
  });
});
