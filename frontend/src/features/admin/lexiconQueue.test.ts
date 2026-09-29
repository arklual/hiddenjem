import { describe, expect, it } from 'vitest';
import type { UnrecognizedDirection } from '@/api/types';
import { queueOrder } from './lexiconQueue';

function row(overrides: Partial<UnrecognizedDirection> & { query: string }): UnrecognizedDirection {
  return {
    normalizedQuery: overrides.query,
    occurrences: 1,
    hadAWayOut: true,
    firstSeen: '2026-01-01T00:00:00Z',
    lastSeen: '2026-01-02T00:00:00Z',
    ...overrides,
  };
}

/**
 * Очередь пополнения словаря направлений.
 *
 * Пробел в словаре в этой сессии находили дважды и оба раза случайно: «кибербезопасность» по-английски
 * не разрешалась ни в одну метку, «биотехнологии» — ни на одном языке. Журнал таких формулировок
 * собирался всё это время, а экрана к нему не было.
 */
describe('queueOrder', () => {
  it('puts a direction the product could not even guess at first', () => {
    // Формулировка с подсказкой стоит аналитику секунд; формулировка без подсказки — это
    // направление, о котором продукт не знает вовсе.
    const ordered = queueOrder([
      row({ query: 'с подсказкой', occurrences: 50, hadAWayOut: true }),
      row({ query: 'без подсказки', occurrences: 2, hadAWayOut: false }),
    ]);

    expect(ordered.map((item) => item.query)).toEqual(['без подсказки', 'с подсказкой']);
  });

  it('orders by how often analysts hit the same hole', () => {
    const ordered = queueOrder([
      row({ query: 'редкая', occurrences: 1, hadAWayOut: false }),
      row({ query: 'частая', occurrences: 20, hadAWayOut: false }),
    ]);

    expect(ordered.map((item) => item.query)).toEqual(['частая', 'редкая']);
  });

  it('is a function of the data and not of the order it arrived in', () => {
    // Два открытия одного экрана обязаны давать один список: иначе куратор не может вернуться к
    // тому, что видел минуту назад.
    const rows = [
      row({ query: 'бета', normalizedQuery: 'бета', occurrences: 3, hadAWayOut: false }),
      row({ query: 'альфа', normalizedQuery: 'альфа', occurrences: 3, hadAWayOut: false }),
    ];

    expect(queueOrder(rows).map((item) => item.query)).toEqual(
      queueOrder([...rows].reverse()).map((item) => item.query),
    );
  });

  it('does not modify the list it was given', () => {
    const rows = [row({ query: 'а', hadAWayOut: true }), row({ query: 'б', hadAWayOut: false })];
    const before = rows.map((item) => item.query);

    queueOrder(rows);

    expect(rows.map((item) => item.query)).toEqual(before);
  });
});
