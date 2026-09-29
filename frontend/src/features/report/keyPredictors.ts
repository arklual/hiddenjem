/**
 * Ключевые предикторы темы — то, за что она попала в отчёт, в одну строку на карточке.
 *
 * ТЗ перечисляет их среди обязательного в интерфейсе: «ключевые предикторы: объяснение отнесения
 * технологии к зарождающемуся тренду», и в макете они стоят прямо в строке списка («Всплеск
 * патентов (Q3), рост упоминаний в arXiv»). Без них то, что **поддерживает** тему, было видно лишь
 * на странице темы, в перечне наблюдений (`observed.ts`).
 *
 * Правила те же, что у наблюдений, и по той же причине:
 *
 * 1. Только величины, которые движок уже посчитал и прислал в диагностике индикаторов.
 * 2. Ни одного оценочного слова: «×2,4 публикаций за год» — факт, «бурный рост» — вывод.
 * 3. Нет данных — нет предиктора. Лучше два честных, чем три с выдуманным.
 *
 * Какие индикаторы брать. Сильнейшие по значению среди измеренных и имеющих вес. Прежде отсюда
 * исключался «ограничитель» методики — он назывался на карточке как то, что снижает балл. Балл
 * теперь считает модель признаков, ограничителя на карточке нет, и исключать его незачем.
 */
import type { IndicatorScore, RankedTrend } from '@/api/types';
import { plural, pluralize } from '@/lib/format';
import { translate as t, type TranslationKey } from '@/lib/i18n/translate';

/** Сколько предикторов показывать: строка в карточке, а не абзац. */
export const KEY_PREDICTOR_LIMIT = 3;

export interface KeyPredictor {
  /** Индикатор, из диагностики которого взяты числа, — для проверки по методологии. */
  indicator: IndicatorScore['name'];
  text: string;
}

function numberAt(source: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = source?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function textAt(source: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = source?.[key];
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

/** Формы слова из словаря: ключ группы, в которой лежат ``one``/``few``/``many``. */
function forms(group: string): { one: string; few: string; many: string } {
  return {
    one: t(`keyPredictors.${group}.one` as TranslationKey),
    few: t(`keyPredictors.${group}.few` as TranslationKey),
    many: t(`keyPredictors.${group}.many` as TranslationKey),
  };
}

function decimal(value: number): string {
  return (Math.round(value * 10) / 10).toLocaleString('ru-RU', { maximumFractionDigits: 1 });
}

/** Фраза-наблюдение для одного индикатора; `null`, если чисел для неё не пришло. */
function phrase(indicator: IndicatorScore, trend: RankedTrend): string | null {
  const d = indicator.diagnostics;
  switch (indicator.name) {
    case 'novelty': {
      const year = numberAt(d, 'firstYear') ?? (trend.firstMentionYear || undefined);
      return year ? t('keyPredictors.firstYear', { year }) : null;
    }
    case 'growth': {
      const factor = numberAt(d, 'periodFactor');
      if (factor === undefined || factor <= 1) return null;
      // Рост относительно направления и рост в штуках — разные утверждения, и подпись обязана
      // говорить, какое из них сделано: иначе ×3 на корпусе, который сам вырос втрое, читается
      // как находка.
      const base = t(
        d?.['relativeToCorpus'] === true ? 'keyPredictors.growthRelative' : 'keyPredictors.growthAbsolute',
        { factor: decimal(factor) },
      );
      const burst = textAt(d, 'burst_startPeriod');
      return burst ? t('keyPredictors.burst', { base, period: burst }) : base;
    }
    case 'diffusion': {
      const orgs = numberAt(d, 'orgCount');
      const classes = numberAt(d, 'sourceClassCount');
      if (orgs === undefined || orgs < 2) return null;
      const organizations = pluralize(orgs, forms('organization'));
      return classes !== undefined && classes > 1
        ? t('keyPredictors.sourceTypes', {
            organizations,
            count: classes,
            types: plural(classes, forms('type')),
          })
        : organizations;
    }
    case 'weakness': {
      const recent = numberAt(d, 'recentDocuments');
      const leader = numberAt(d, 'recentMaxObserved') ?? numberAt(d, 'recentMax');
      const window = numberAt(d, 'window');
      if (recent === undefined || leader === undefined || leader <= recent) return null;
      return t('keyPredictors.weakness', {
        documents: pluralize(recent, forms('work')),
        span: window ? t('keyPredictors.span', { years: window }) : '',
        leader,
      });
    }
    case 'impact': {
      const patents = numberAt(d, 'patentDocuments');
      if (patents !== undefined && patents > 0) {
        return pluralize(patents, forms('patent'));
      }
      const industry = numberAt(d, 'industryShare');
      return industry !== undefined && industry > 0
        ? t('keyPredictors.industry', { percent: Math.round(industry * 100) })
        : null;
    }
    case 'coherence':
      // Связность — свойство формулировки, а не довод за технологию: её числа (NPMI, косинус)
      // на карточке читателю ничего не говорят. Место на странице темы, в разборе балла.
      return null;
    default:
      return null;
  }
}

/** До трёх ключевых предикторов темы, от сильнейшего индикатора к слабейшему. */
export function keyPredictors(trend: RankedTrend): KeyPredictor[] {
  const candidates = trend.assessment.indicators
    .filter((indicator) => indicator.weight > 0 && indicator.value > 0)
    // Устойчивый порядок при равных значениях: две темы с одинаковыми числами читаются одинаково.
    .map((indicator, position) => ({ indicator, position }))
    .sort((a, b) => b.indicator.value - a.indicator.value || a.position - b.position)
    .map(({ indicator }) => indicator);

  const result: KeyPredictor[] = [];
  for (const indicator of candidates) {
    const text = phrase(indicator, trend);
    if (text) result.push({ indicator: indicator.name, text });
    if (result.length >= KEY_PREDICTOR_LIMIT) break;
  }
  return result;
}
