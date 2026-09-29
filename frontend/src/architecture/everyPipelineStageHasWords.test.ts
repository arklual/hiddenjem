import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * У каждой стадии конвейера есть слова для аналитика — и ни одной лишней подписи.
 *
 * Панель «почему этой темы нет» рисует путь термина по списку стадий, который присылает движок:
 * своя копия списка молча отстала бы в день появления новой стадии и продолжила бы выглядеть
 * правильной. Названия стадий панель берёт из своей карты, и запасной путь для незнакомой стадии —
 * показать её ключ как есть. Это верно как поведение и негодно как состояние.
 *
 * Замер показал, чего это стоило: движок объявляет пятнадцать стадий, панель знала четырнадцать.
 * Недостающей была `suppressed` — тема, скрытая **собственной пометкой аналитика**. То есть на самый
 * вероятный вопрос («я это пометил, но почему его нет?») продукт отвечал машинным ключом.
 *
 * Проверка двусторонняя, и это не педантизм: односторонняя пропускала бы ровно один из двух видов
 * расхождения, а какой именно — решал бы случай. Урок записан в §39: у соответствия двух списков
 * всегда два направления, и второе обычно про то, чего быть не должно.
 */
const ROOT = resolve(__dirname, '..', '..', '..');
const PIPELINE = join(
  ROOT,
  'services',
  'analytics-service',
  'src',
  'horizon_analytics',
  'domain',
  'pipeline.py',
);
const PANEL = resolve(__dirname, '..', 'features', 'report', 'explainStages.ts');

/** Словарь стадий движка — единственный источник правды: панель его не дублирует, а сопровождает. */
function engineStages(): string[] {
  const source = readFileSync(PIPELINE, 'utf8');
  const start = source.indexOf('TRACE_STAGES: tuple[str, ...] = (');
  const end = source.indexOf(')', start);

  return [...source.slice(start, end).matchAll(/"([a-z_]+)"/g)]
    .map((match) => match[1])
    .filter((stage): stage is string => stage !== undefined);
}

/** Стадии, для которых у панели есть русское название. */
function panelStages(): string[] {
  const source = readFileSync(PANEL, 'utf8');
  const start = source.indexOf('const STAGE_TITLES');
  const end = source.indexOf('};', start);

  return [...source.slice(start, end).matchAll(/^ {2}([a-z_]+):/gm)]
    .map((match) => match[1])
    .filter((stage): stage is string => stage !== undefined);
}

describe('the explanation panel speaks of every stage the engine reports', () => {
  it('reads a plausible number of stages from the engine', () => {
    // Канарейка разборщика: пустой список сделал бы обе проверки ниже вечнозелёными.
    expect(engineStages().length).toBeGreaterThan(10);
  });

  it('reads a plausible number of titles from the panel', () => {
    expect(panelStages().length).toBeGreaterThan(10);
  });

  it('has words for every stage the engine can report', () => {
    const titled = new Set(panelStages());

    expect(engineStages().filter((stage) => !titled.has(stage))).toEqual([]);
  });

  it('keeps no title for a stage the engine no longer has', () => {
    // Подпись, пережившая свою стадию, — обещание объяснить то, чего не бывает. Читатель примет её
    // за действующую часть конвейера и будет искать её в отчёте.
    const known = new Set(engineStages());

    expect(panelStages().filter((stage) => !known.has(stage))).toEqual([]);
  });
});
