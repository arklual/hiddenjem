import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Каждый путь контракта достижим из интерфейса — или объявлен недостижимым намеренно.
 *
 * Правило написано по следу находки. Из 39 путей контракта интерфейс достигал 38; единственный
 * недостижимый — журнал формулировок, которых не знает перекрёстный словарь направлений. Данные
 * копились с самого начала, а экрана к ним не было, и пробелы в словаре находились случайно: дважды
 * за одну сессию.
 *
 * Возможность, до которой нет пути из интерфейса, ничем не отличается от нереализованной — с той
 * разницей, что за неё уже заплачено разработкой, миграцией и поддержкой. Отсутствие пути не
 * выглядит ошибкой: тесты зелёные, контракт полон, экран просто не существует.
 */
const ROOT = resolve(__dirname, '..', '..', '..');
const CONTRACT = join(ROOT, 'contracts', 'openapi', 'horizon-api.yaml');
const API_DIR = resolve(__dirname, '..', 'api');

/**
 * Пути, до которых интерфейс не ходит намеренно, — с причиной у каждого.
 *
 * Список без причин превращается в место, куда сваливают неудобное.
 */
// Пусто с удалением входа: единственное исключение — обновление токена транспортом — ушло из
// контракта вместе со всеми путями `/auth/*`.
const DELIBERATELY_UNREACHABLE = new Map<string, string>();

/** Пути контракта: ключи верхнего уровня внутри `paths:`. */
function contractPaths(): string[] {
  const text = readFileSync(CONTRACT, 'utf8');
  return [...text.matchAll(/^ {2}(\/api\/v1\/[^\s:]*):/gm)]
    .map((match) => match[1])
    .filter((path): path is string => path !== undefined);
}

export interface Operation {
  readonly method: string;
  readonly path: string;
}

/**
 * Операции контракта — пара «метод + путь», а не путь.
 *
 * Первая редакция сверяла пути, и этого оказалось мало. У `/api/v1/methodology/profiles` объявлены
 * `GET` и `POST`; интерфейс вызывал только `GET`, и правило оставалось зелёным, потому что путь
 * встречался. Возможность задать свои веса индикаторов — оплаченная бэкендом, контрактом, ролью и
 * миграцией — была недостижима, и проверка, написанная ровно против этого, её не видела. (Позже
 * методика с профилями выведена из продукта целиком, но урок о паре «метод + путь» остался.)
 */
function contractOperations(): Operation[] {
  const operations: Operation[] = [];
  let path: string | null = null;
  for (const line of readFileSync(CONTRACT, 'utf8').split('\n')) {
    const pathMatch = /^ {2}(\/api\/v1\/[^\s:]*):/.exec(line);
    if (pathMatch?.[1] !== undefined) {
      path = pathMatch[1];
      continue;
    }
    const methodMatch = /^ {4}(get|post|put|patch|delete):/.exec(line);
    if (methodMatch?.[1] !== undefined && path !== null) {
      operations.push({ method: methodMatch[1].toUpperCase(), path });
    }
  }
  return operations;
}

/**
 * Обращения клиента к сети: метод и адрес.
 *
 * Берутся три формы, которыми продукт действительно ходит на сервер: `http.request`, `http.requestBlob`
 * (выгрузка файла) и построитель адреса для `EventSource` — поток прогресса идёт по SSE, а не через
 * `fetch`, и без него правило объявило бы живую возможность недостижимой.
 */
function clientCalls(): Operation[] {
  const source = readdirSync(API_DIR)
    .filter((name) => name.endsWith('.ts') && name !== 'generated.ts' && !name.includes('.test.'))
    .map((name) => readFileSync(join(API_DIR, name), 'utf8'))
    .join('\n');

  const calls: Operation[] = [];
  for (const match of source.matchAll(
    /(?:http\.request\w*)\(\s*`([^`]*)`([\s\S]{0,300}?)\)\s*[;,]/g,
  )) {
    const url = match[1];
    if (url === undefined) continue;
    calls.push({ method: /method:\s*'([A-Z]+)'/.exec(match[2] ?? '')?.[1] ?? 'GET', path: url });
  }
  for (const match of source.matchAll(/return `(\$\{API\}[^`]*)`/g)) {
    if (match[1] !== undefined) calls.push({ method: 'GET', path: match[1] });
  }
  return calls;
}

/** Сегменты пути без параметров — по ним адрес клиента сопоставляется с путём контракта. */
function segmentsOf(path: string): string[] {
  return path
    .slice('/api/v1'.length)
    .split('/')
    .filter((segment) => segment && !segment.startsWith('{'));
}

/**
 * Только те файлы, которые действительно ходят в сеть.
 *
 * Первая редакция читала весь каталог `api`, и проверка оказалась снисходительной: подмена адреса
 * вызова на несуществующий прошла незамеченной, потому что имя пути встречалось в ключах запросов.
 * Совпадение строки где-нибудь рядом — не доказательство вызова.
 */
function callSites(): string {
  return readdirSync(API_DIR)
    .filter((name) => name.endsWith('.ts') && name !== 'generated.ts' && !name.includes('.test.'))
    .map((name) => readFileSync(join(API_DIR, name), 'utf8'))
    .filter((text) => text.includes('http.request'))
    .join('\n');
}

describe('the interface can reach the whole contract', () => {
  it('reads call sites and not the whole client folder', () => {
    // Вторая канарейка: если фильтр перестанет находить вызовы, проверка ниже станет
    // вечнозелёной — она сверяла бы пути контракта с пустой строкой.
    expect(callSites().length).toBeGreaterThan(1000);
  });

  it('reads a plausible number of paths from the contract', () => {
    // Канарейка разборщика. Пустой список путей сделал бы проверку ниже вечнозелёной: она
    // утверждала бы «все пути достижимы», не зная ни одного. Порог снижен с 30 до 20 вместе с
    // удалением входа, профиля и администрирования пользователей: их пути ушли из контракта.
    expect(contractPaths().length).toBeGreaterThan(20);
  });

  it('has a client call for every path', () => {
    const client = callSites();

    const unreachable = contractPaths()
      .filter((path) => !DELIBERATELY_UNREACHABLE.has(path))
      .filter((path) => !segmentsOf(path).every((segment) => client.includes(segment)));

    expect(unreachable).toEqual([]);
  });

  it('reads a plausible number of operations and of calls', () => {
    // Канарейки разборщиков: пустой список операций сделал бы проверку ниже вечнозелёной, а
    // пустой список вызовов — вечнокрасной, и оба состояния выглядели бы как вывод о продукте.
    // Пороги снижены вместе с удалением входа и администрирования пользователей (было 40 и 35).
    expect(contractOperations().length).toBeGreaterThan(25);
    expect(clientCalls().length).toBeGreaterThan(25);
  });

  it('has a client call for every operation, not merely for every path', () => {
    // Путь мало что доказывает: у `/methodology/profiles` объявлены GET и POST, интерфейс звал
    // только GET, и проверка по путям была зелёной, пока возможность задать свои веса оставалась
    // недостижимой.
    const calls = clientCalls();

    const unreachable = contractOperations()
      .filter((operation) => !DELIBERATELY_UNREACHABLE.has(operation.path))
      .filter(
        (operation) =>
          !calls.some(
            (call) =>
              call.method === operation.method &&
              segmentsOf(operation.path).every((segment) => call.path.includes(segment)),
          ),
      )
      .map((operation) => `${operation.method} ${operation.path}`);

    expect(unreachable).toEqual([]);
  });

  it('drops an exception once its path is gone from the contract', () => {
    const paths = new Set(contractPaths());

    expect([...DELIBERATELY_UNREACHABLE.keys()].filter((path) => !paths.has(path))).toEqual([]);
  });
});
