import { describe, expect, it } from 'vitest';
import { en } from './en';
import { ru } from './ru';

/**
 * Английский словарь покрывает русский целиком.
 *
 * Английский — выбираемая локаль: `LOCALES = ['ru', 'en']`. Поиск по
 * ключам показал, что **57 ключей из 166 в нём отсутствовали** — треть словаря, — и продукт этого не
 * сообщал: `translate` падает обратно на русский, поэтому англоязычный пользователь получал
 * работающий интерфейс, треть которого на незнакомом языке. Ни теста, ни предупреждения, ни способа
 * узнать, какая именно треть.
 *
 * Больше всего недоставало там, где дороже всего: **22 из 57 недостающих ключей — группа `warning`**.
 * Оговорки — то, ради чего этот продукт устроен так, как устроен: «низкая доказательная база»,
 * «корпус рассмотрен не целиком», «направление не распознано», «результат из кэша». Отчёт без них
 * выглядит увереннее, чем есть, и именно они не переводились.
 *
 * Запасной путь на русский остаётся — он верен для случая, когда бэкенд прислал значение
 * перечисления, которого сборка не знает. Но «перевода нет» и «значение неизвестно» — разные вещи, и
 * первое не должно прятаться за вторым.
 */
type Node = Record<string, unknown>;

function flatten(source: Node, prefix = ''): string[] {
  return Object.entries(source).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return typeof value === 'object' && value !== null ? flatten(value as Node, path) : [path];
  });
}

describe('the English dictionary covers the Russian one', () => {
  it('reads a plausible number of keys', () => {
    // Канарейка обхода: пустой список ключей сделал бы проверку ниже вечнозелёной — она
    // утверждала бы «переведено всё», не зная ни одного ключа.
    expect(flatten(ru).length).toBeGreaterThan(150);
  });

  it('has an English string for every Russian key', () => {
    const english = new Set(flatten(en));

    expect(flatten(ru).filter((key) => !english.has(key))).toEqual([]);
  });

  it('adds no key the Russian reference does not have', () => {
    // Обратная сторона: русский словарь задаёт тип `TranslationKey`, и ключ, существующий только
    // в английском, недостижим — мёртвая строка, которую никто не удалит, потому что её никто не
    // ищет.
    const russian = new Set(flatten(ru));

    expect(flatten(en).filter((key) => !russian.has(key))).toEqual([]);
  });

  it('leaves no English value equal to its Russian original', () => {
    // Копия русской строки в английском словаре проходит проверку выше и не переводит ничего.
    // Исключение — значения, одинаковые в обоих языках по существу: тире и латинские сокращения.
    const russian = new Map(flatten(ru).map((key) => [key, valueAt(ru, key)]));
    const cyrillic = /[а-яё]/i;

    const untranslated = flatten(en).filter((key) => {
      const value = valueAt(en, key);
      return value === russian.get(key) && cyrillic.test(value);
    });

    expect(untranslated).toEqual([]);
  });
});

function valueAt(source: Node, path: string): string {
  let current: unknown = source;
  for (const segment of path.split('.')) {
    current = (current as Node)[segment];
  }
  return String(current);
}
