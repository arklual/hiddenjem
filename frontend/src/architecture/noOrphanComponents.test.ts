import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Компонент, которым никто не пользуется, — не «пока не подключён», а невидимая пропажа.
 *
 * Это правило написано по следу настоящей ошибки. Оговорку о неполноте корпуса я вынес отдельным
 * компонентом и покрыл тестом, а потом проверил подменой, что будет, если снять её со страницы
 * отчёта. Тесты остались зелёными: компонент проверял сам себя, а собран ли он в страницу — не
 * проверял никто. Линтер ловит половину случая (остаётся висячий импорт) и молчит, когда убраны обе
 * строки. Так дыра выглядела у всех четырнадцати вынесенных компонентов сразу, а не у одного.
 *
 * Правило структурное, потому что дефект структурный: дело не в поведении компонента, а в том, что
 * он ни к чему не прикреплён. Проверять это через рендер страницы означало бы поднимать роутер,
 * клиент запросов и живой бэкенд ради вопроса «есть ли ссылка».
 */
const SRC = resolve(__dirname, '..');

/** Точки входа: на них ссылается не код, а сборщик и index.html. */
const ENTRY_POINTS = new Set(['main.tsx', 'App.tsx']);

/**
 * Известные сироты — долг, а не разрешение. Список заморожен: новый компонент сюда не добавляют,
 * его подключают. Каждая запись живёт с датой и причиной, и тест ниже требует, чтобы запись
 * исчезала вместе с файлом, — иначе список превращается в кладбище прошлых оправданий.
 */
const KNOWN_ORPHANS = new Map([
  [
    'components/Tabs.tsx',
    'заготовка без пользователя с 2026-08-09; удалить, когда станет ясно, для какой страницы',
  ],
]);

function tsxFilesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      return tsxFilesUnder(full);
    }
    return full.endsWith('.tsx') && !full.endsWith('.test.tsx') ? [full] : [];
  });
}

/**
 * Ссылка засчитывается только из рабочего кода. Импорт из теста — это и есть разбираемый случай:
 * компонент, живущий исключительно ради собственной проверки.
 */
function productionSources(): { path: string; text: string }[] {
  return tsxFilesUnder(SRC)
    .concat(
      readdirSync(SRC, { recursive: true, encoding: 'utf8' })
        .filter((e) => e.endsWith('.ts') && !e.endsWith('.test.ts') && !e.endsWith('.d.ts'))
        .map((e) => join(SRC, e)),
    )
    .map((path) => ({ path, text: readFileSync(path, 'utf8') }));
}

/**
 * Сам поиск — чистая функция от списка файлов, а не от диска. Иначе его нельзя проверить: правило
 * с пустым ожидаемым списком выглядит одинаково и когда всё подключено, и когда оно ослепло.
 * Ровно это и показала подмена: тело поиска заменено на «сирот нет» — оба теста остались зелёными.
 */
export function orphansAmong(
  components: string[],
  sources: { path: string; text: string }[],
): string[] {
  return components
    .filter((file) => !ENTRY_POINTS.has(file.split('/').pop() ?? ''))
    .filter((file) => {
      const name = (file.split('/').pop() ?? '').replace('.tsx', '');
      // Ссылкой считается упоминание имени в любом другом рабочем файле: импорт, ленивая
      // загрузка, объявление маршрута. Точнее разбирать нечего — нам важен сам факт связи.
      return !sources.some((s) => s.path !== file && new RegExp(`\\b${name}\\b`).test(s.text));
    });
}

describe('components are wired into the product', () => {
  it('has no component that only its own test uses', () => {
    const sources = productionSources();
    const components = tsxFilesUnder(SRC).map((file) => relative(SRC, file));

    const orphans = orphansAmong(
      components,
      sources.map((s) => ({ path: relative(SRC, s.path), text: s.text })),
    ).filter((file) => !KNOWN_ORPHANS.has(file));

    expect(orphans).toEqual([]);
  });

  it('finds a component nobody but its own test mentions', () => {
    // Канарейка детектора. Без неё «сирот нет» означает и «всё подключено», и «искать перестали»,
    // а различить эти два состояния по зелёному тесту невозможно.
    const orphans = orphansAmong(
      ['features/report/Wired.tsx', 'features/report/Lonely.tsx'],
      [
        { path: 'features/report/ReportPage.tsx', text: "import { Wired } from './Wired';" },
        { path: 'features/report/Wired.tsx', text: 'export function Wired() {}' },
        { path: 'features/report/Lonely.tsx', text: 'export function Lonely() {}' },
      ],
    );

    expect(orphans).toEqual(['features/report/Lonely.tsx']);
  });

  it('counts a mention from a page as wiring, not just an import line', () => {
    // Маршруты подключают страницы не импортом рядом с разметкой, а ссылкой в таблице маршрутов.
    // Правило, требующее именно импорта, объявило бы сиротами все страницы разом.
    const orphans = orphansAmong(
      ['features/saved/RadarPage.tsx'],
      [{ path: 'routes.tsx', text: 'const RadarPage = lazy(() => import("./features/saved/RadarPage"));' }],
    );

    expect(orphans).toEqual([]);
  });

  it('drops an exception once its file is gone', () => {
    // Иначе список растёт молча: файл удалили, оправдание осталось, и следующая сирота с тем же
    // путём проезжает мимо правила.
    const present = tsxFilesUnder(SRC).map((file) => relative(SRC, file));

    expect([...KNOWN_ORPHANS.keys()].filter((known) => !present.includes(known))).toEqual([]);
  });
});
