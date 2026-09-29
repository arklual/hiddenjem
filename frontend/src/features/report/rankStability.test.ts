import { describe, expect, it } from 'vitest';
import { verdictFor } from './rankStability';

/**
 * Разница между «вывод о направлении» и «вывод о наших настройках».
 *
 * Тема, держащаяся в отчёте при любом взвешивании, и тема, вылетающая при удвоении одного веса,
 * выглядят на экране одинаково — обе просто занимают место в списке. Это правило их различает.
 */
describe('verdictFor', () => {
  it('calls a topic immovable only when it never moved at all', () => {
    expect(verdictFor({ best: 3, worst: 3 }, 15)).toBe('immovable');
    // Разница в одно место, но утверждения разные: «место не зависит от весов» и «место держится,
    // пока веса примерно такие». Второе слабее, и выдавать его за первое нельзя.
    expect(verdictFor({ best: 3, worst: 4 }, 15)).toBe('holds');
  });

  it('separates a topic that stays in the report from one that falls out of it', () => {
    expect(verdictFor({ best: 2, worst: 15 }, 15)).toBe('holds');
    expect(verdictFor({ best: 2, worst: 16 }, 15)).toBe('slips');
  });

  it('says nothing about a report that was never measured', () => {
    // Отчёты, выпущенные до появления проверки, диапазона не несут. Вывод об устойчивости для них
    // был бы утверждением о том, чего никто не измерял.
    expect(verdictFor(undefined, 15)).toBeNull();
  });

  it('judges against the report the analyst is looking at, not a fixed fifteen', () => {
    // Аналитик может запросить пять тем или пятьдесят. «Выпадает из отчёта» означает выпадение из
    // его отчёта, а не из отчёта размера по умолчанию.
    expect(verdictFor({ best: 1, worst: 6 }, 5)).toBe('slips');
    expect(verdictFor({ best: 1, worst: 6 }, 50)).toBe('holds');
  });
});
