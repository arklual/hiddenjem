import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Каждое поле тела запроса кто-то заполняет — или объявлено неиспользуемым с причиной.
 *
 * Четвёртый уровень одной и той же ошибки, и все четыре найдены по очереди:
 *
 * | правило видело | не видело |
 * | --- | --- |
 * | пути контракта | операции: `POST` на пути, где звали `GET` |
 * | ключи словаря | строку, пришедшую по проводу |
 * | контракт → маршрут | маршрут → вне контракта |
 * | операции | **поле тела операции** |
 *
 * Последний случай стоил дня. Возможность задать свои веса была сделана наполовину: профиль
 * создавался и показывался, а выбрать его при запуске анализа было нельзя — `methodologyProfileId`
 * не заполнял ни один экран (позже методика с профилями выведена из продукта целиком). Проверка
 * достижимости операций молчала и не могла не молчать:
 * `POST /research-requests` достижим, недостижимо было поле его тела.
 *
 * Возможность с половиной пути ничем не отличается от отсутствующей — с той разницей, что за неё
 * уже заплачено разработкой, контрактом и хранением.
 */
const ROOT = resolve(__dirname, '..', '..', '..');
const CONTRACT = join(ROOT, 'contracts', 'openapi', 'horizon-api.yaml');
const SRC = resolve(__dirname, '..');

/** Файлы, повторяющие контракт: читать их — значит сверять контракт с самим собой. */
const MIRRORS = new Set(['generated.ts', 'schemas.ts', 'types.ts']);

/**
 * Поля, которые интерфейс не заполняет намеренно, — с причиной у каждого.
 *
 * Список без причин превращается в место, куда сваливают неудобное.
 */
// Пусто с удалением входа: оба прежних исключения — тело обновления токена и slug организации при
// регистрации — ушли из контракта вместе со своими операциями.
const DELIBERATELY_UNUSED = new Map<string, string>();

/** Схемы, на которые ссылается хотя бы один `requestBody`. */
function requestSchemas(): string[] {
  const lines = readFileSync(CONTRACT, 'utf8').split('\n');
  const found = new Set<string>();
  for (let index = 0; index < lines.length; index += 1) {
    if (!/requestBody:/.test(lines[index] ?? '')) continue;
    for (let look = index; look < Math.min(lines.length, index + 8); look += 1) {
      const match = /\$ref: '#\/components\/schemas\/([A-Za-z]+)'/.exec(lines[look] ?? '');
      if (match?.[1] !== undefined) {
        found.add(match[1]);
        break;
      }
    }
  }
  return [...found].sort();
}

/** Текст блока схемы — от её заголовка до следующей схемы того же отступа. */
function blockOf(schema: string): string {
  const text = readFileSync(CONTRACT, 'utf8');
  const start = text.indexOf(`\n    ${schema}:`);
  if (start < 0) return '';
  const rest = text.slice(start + 1);
  const end = rest.search(/\n {4}[A-Za-z]+:/);
  return end < 0 ? rest : rest.slice(0, end);
}

/** Свойства схемы: ключи блока `properties:` на его отступе. */
function propertiesOf(schema: string): string[] {
  const block = blockOf(schema);
  const properties = block.indexOf('properties:');
  if (properties < 0) return [];
  return [...block.slice(properties).matchAll(/^ {8}([a-zA-Z]+):/gm)]
    .map((match) => match[1])
    .filter((name): name is string => name !== undefined);
}

/**
 * Схемы, до которых доходит тело запроса, — вместе со вложенными по `$ref`.
 *
 * Первая редакция читала только собственные свойства схемы запроса, и проба это вскрыла: у
 * `SubmitResearchRequest` поле `parameters` — ссылка, а все параметры анализа, включая
 * `methodologyProfileId`, лежат в `AnalysisParameters`. То есть правило, написанное ради этого поля,
 * его не проверяло.
 */
function reachableSchemas(): string[] {
  const seen = new Set<string>();
  const queue = requestSchemas();
  while (queue.length > 0) {
    const schema = queue.shift();
    if (schema === undefined || seen.has(schema)) continue;
    seen.add(schema);
    for (const match of blockOf(schema).matchAll(/\$ref: '#\/components\/schemas\/([A-Za-z]+)'/g)) {
      if (match[1] !== undefined && !seen.has(match[1])) queue.push(match[1]);
    }
  }
  return [...seen].sort();
}

/**
 * Код интерфейса без зеркал контракта.
 *
 * `schemas.ts`, `types.ts` и `generated.ts` повторяют контракт по определению: имя любого поля
 * встречается там всегда, и правило, читающее их, зелено при любом состоянии продукта. Ровно так
 * ошиблась первая редакция проверки достижимости — она читала весь каталог `api` и принимала
 * совпадение строки в ключах запросов за доказательство вызова.
 *
 * Проверено: без этого исключения правило не поймало бы случай, ради которого написано —
 * `methodologyProfileId` жил в зеркале и не заполнялся ни одним экраном.
 *
 * Огрубление остаётся: имя ищется как слово где угодно в экранах и клиенте, а не только в месте
 * сборки тела запроса. Правило ловит «не используется вовсе» и не ловит «упоминается, но не доходит
 * до запроса». Ошибается оно в сторону молчания, а не ложной тревоги, и это верный размен: цена
 * ложной тревоги здесь — недоверие к правилу целиком.
 */
function clientSource(): string {
  const parts: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (
        /\.(ts|tsx)$/.test(entry.name) &&
        !MIRRORS.has(entry.name) &&
        !entry.name.includes('.test.')
      ) {
        parts.push(readFileSync(full, 'utf8'));
      }
    }
  };
  walk(SRC);
  return parts.join('\n');
}

describe('the interface fills every request field the contract declares', () => {
  it('reads a plausible number of request schemas', () => {
    // Канарейка разборщика: пустой список сделал бы проверку ниже вечнозелёной — она утверждала бы
    // «все поля используются», не зная ни одного. Порог снижен с пяти до трёх вместе с удалением
    // входа: тела регистрации, входа и обновления токена ушли из контракта.
    expect(requestSchemas().length).toBeGreaterThan(3);
  });

  it('reads the properties of a schema it knows by hand', () => {
    // Вторая канарейка: разборщик свойств легко ломается об отступы, и его молчание выглядело бы
    // как отсутствие полей.
    expect(propertiesOf('AnalysisParameters')).toContain('mode');
  });

  it('has a call site for every request field', () => {
    const code = clientSource();

    const unused = reachableSchemas().flatMap((schema) =>
      propertiesOf(schema)
        .map((property) => `${schema}.${property}`)
        .filter((field) => !DELIBERATELY_UNUSED.has(field))
        .filter((field) => {
          const property = field.slice(field.indexOf('.') + 1);
          return !new RegExp(`\\b${property}\\b`).test(code);
        }),
    );

    expect(unused).toEqual([]);
  });

  it('drops an exception once its field is gone from the contract', () => {
    // Исключение, пережившее своё поле, — запись о том, чего нет, и следующий читатель примет её
    // за действующее решение.
    const declared = new Set(
      reachableSchemas().flatMap((schema) =>
        propertiesOf(schema).map((property) => `${schema}.${property}`),
      ),
    );

    expect([...DELIBERATELY_UNUSED.keys()].filter((field) => !declared.has(field))).toEqual([]);
  });
});
