import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ru } from '@/lib/i18n/ru';

/**
 * «Зрелая» и «мейнстримная» — разные вещи, и продукт не должен называть их одним словом.
 *
 * Галочка на форме поиска управляет полем `includeMature`, а оно решает только одно: применять ли
 * BRULE-3, отсев темы, уже занявшей заметную долю литературы направления. Стадию жизненного цикла
 * она не фильтрует. Значок «Зрелая» на карточке означает совсем другое — замедлившийся рост
 * (β ≤ 0,20).
 *
 * Замер по эталонному корпусу: при снятой галочке 33 темы из 90 несут значок «Зрелая». Читатель,
 * снявший «Включая зрелые темы» и увидевший треть отчёта со значком «Зрелая», делает единственный
 * возможный вывод — фильтр не работает. Он работает; совпадало слово.
 *
 * Проверка структурная, потому что дефект структурный: подпись легко вернуть обратно, и ни один
 * тест поведения этого не заметит — поведение не менялось и не должно было.
 */
const SEARCH_PAGE = resolve(__dirname, 'NewAnalysisPage.tsx');

function searchPageSource(): string {
  return readFileSync(SEARCH_PAGE, 'utf8');
}

describe('the mainstream filter does not borrow the word the lifecycle badge uses', () => {
  it('keeps the lifecycle badge calling MATURING «Зрелая»', () => {
    // Канарейка: если значок переименуют, проверка ниже потеряет предмет и станет вечнозелёной,
    // продолжая называться защитой от совпадения слов.
    expect(ru.lifecycle.MATURING).toBe('Зрелая');
  });

  it('labels the filter by what it filters', () => {
    expect(searchPageSource()).toContain('Включать мейнстримные темы');
  });

  it('never calls the filter by the badge’s word', () => {
    // Страница целиком, а не только подпись переключателя: в Hiddenjem подпись — текст разметки, и
    // «зрелые» в подсказке рядом читались бы так же, как на самой галочке.
    const word = /зрел/i.exec(searchPageSource());

    expect(word?.[0] ?? null).toBeNull();
  });

  it('reads the page it claims to read', () => {
    // Вторая канарейка: файл, переехавший или переименованный, оставил бы обе проверки выше
    // сверяющими пустую строку.
    expect(searchPageSource()).toContain('includeMature');
  });
});
