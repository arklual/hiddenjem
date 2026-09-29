import { expect, test, type APIRequestContext } from '@playwright/test';
import { apiContext, runAnalysis, safeBody } from '../support/api';

/**
 * «Мы не поняли вопроса» — отдельное состояние продукта, а не разновидность пустого ответа.
 *
 * Отнесение темы к направлению держится на предметных кодах источников, а они английские. Русская
 * формулировка попадает в направление через перекрёстный словарь; формулировки, которой в словаре
 * нет, система не понимает — и обязана сказать это, а не выдать правдоподобный отчёт не о том.
 *
 * Цепочка собиралась четырьмя итерациями и до сих пор проверена только модульно, на каждом звене
 * по отдельности: движок считает признак, контракт его несёт, сборщик кладёт в отчёт, выгрузка
 * печатает оговорку, журнал копит спрос. Здесь она проверяется целиком и на настоящем стеке —
 * ровно там, где ошибка на стыке и живёт.
 *
 * Формулировки выбраны по эталонному корпусу, а не наугад:
 * «квантовые вычисления» — статья словаря есть; «квантовый компьютинг» — статьи нет, но общие слова
 * со статьями есть, поэтому подсказка обязана быть; «селекция озимой пшеницы» — ни статьи, ни общих
 * слов, поэтому подсказки быть не должно.
 */

const RECOGNISED = 'квантовые вычисления';
const NEAR_MISS = 'квантовый компьютинг';
const NOTHING_ALIKE = 'селекция озимой пшеницы';

async function report(api: APIRequestContext, query: string): Promise<Record<string, unknown>> {
  const run = await runAnalysis(api, query);
  expect(run.status, `анализ «${query}» не завершился`).toBe('COMPLETED');
  const response = await api.get(`/api/v1/reports/${run.reportId}`);
  expect(response.ok(), await safeBody(response)).toBeTruthy();
  return response.json();
}

test.describe('Распознавание направления', () => {
  test('известная формулировка приходит распознанной и без подсказок', async () => {
    // Обратная ошибка не менее вредна: оговорка, срабатывающая не по делу, обесценивает те, где
    // она по делу, а предлагать переформулировать удавшийся запрос — совет, читающийся как
    // «что-то не так», когда всё так.
    const api = await apiContext();
    const body = await report(api, RECOGNISED);
    const coverage = body.coverage as Record<string, unknown>;

    expect(coverage.directionRecognized).not.toBe(false);
    expect((coverage.directionSuggestions as string[] | undefined) ?? []).toHaveLength(0);
  });

  test('неизвестная формулировка помечена, и предложены ближайшие известные', async () => {
    // Без подсказки красная плашка «направление не распознано» — тупик: разница между работающим
    // продуктом и бесполезным здесь в одном слове, которое аналитику неоткуда узнать.
    const api = await apiContext();
    const body = await report(api, NEAR_MISS);
    const coverage = body.coverage as Record<string, unknown>;

    expect(coverage.directionRecognized).toBe(false);
    expect(coverage.directionSuggestions as string[]).toContain('квантовые вычисления');
  });

  test('оговорка не делает покрытие неполным', async () => {
    // Две разные оговорки об одном отчёте. Источники были доступны и корпус собран целиком:
    // неверно понят вопрос, а не данные. Смешать их значит потерять оба смысла.
    const api = await apiContext();
    const coverage = (await report(api, NEAR_MISS)).coverage as Record<string, unknown>;

    expect(coverage.directionRecognized).toBe(false);
    expect(coverage.partial).toBe(false);
  });

  test('без единой похожей формулировки подсказка не выдумывается', async () => {
    // Подсказка наугад хуже её отсутствия: она выглядит как знание.
    const api = await apiContext();
    const coverage = (await report(api, NOTHING_ALIKE)).coverage as Record<string, unknown>;

    expect(coverage.directionRecognized).toBe(false);
    expect((coverage.directionSuggestions as string[] | undefined) ?? []).toHaveLength(0);
  });

  test('записка несёт оговорку до списка тем, а не после него', async () => {
    // Записка ходит по почте отдельно от интерфейса. Оговорку, прочитанную после пятнадцати
    // обоснованных тем, читают уже после решения.
    const api = await apiContext();
    const run = await runAnalysis(api, NEAR_MISS);
    const response = await api.get(`/api/v1/reports/${run.reportId}/export?format=markdown`);
    expect(response.ok(), await safeBody(response)).toBeTruthy();
    const briefing = await response.text();

    const warning = briefing.indexOf('Направление не распознано');
    const topics = briefing.indexOf('## Темы');
    expect(warning, 'предупреждение о нераспознанном направлении').toBeGreaterThanOrEqual(0);
    expect(topics).toBeGreaterThanOrEqual(0);
    expect(warning).toBeLessThan(topics);
  });

  test('записка объясняет, почему цитаты не переведены', async () => {
    // Формулировки проблемы и пользы — дословные цитаты, часто на языке источника. Читатель без
    // этого правила видит недоделку там, где на самом деле прослеживаемость до первоисточника, а к
    // записке документацию не приложат.
    const api = await apiContext();
    const run = await runAnalysis(api, RECOGNISED);
    const response = await api.get(`/api/v1/reports/${run.reportId}/export?format=markdown`);
    expect(response.ok(), await safeBody(response)).toBeTruthy();
    const briefing = await response.text();

    expect(briefing).toContain('дословные цитаты из источников');
    // Форм две, и обе намеренные: «цитата» — когда формулировка записана абзацем целиком,
    // «цитаты» со списком — когда она собрана из предложений разных статей, у каждого свой
    // источник. Проверяется то, ради чего правило существует: формулировка стоит в кавычках и
    // подписана как цитата, а не пересказана.
    expect(briefing).toMatch(/- Проблема — цитат[аы]/);
    expect(briefing).toMatch(/«[^»]+»/);
  });

  test('очередь пополнения словаря открыта и отвечает по контракту', async () => {
    // Входа нет, организация одна: очередь читается без авторизации. Проверяется форма ответа —
    // куратору нужна частота и то, был ли у аналитика выход, иначе очередь не отсортировать.
    const api = await apiContext();

    const response = await api.get('/api/v1/admin/unrecognized-directions?limit=50');
    expect(response.status(), await safeBody(response)).toBe(200);
    const rows = (await response.json()) as Array<Record<string, unknown>>;
    expect(Array.isArray(rows)).toBe(true);
    for (const row of rows) {
      expect(typeof row.query).toBe('string');
      expect(typeof row.normalizedQuery).toBe('string');
      expect(row.occurrences as number).toBeGreaterThanOrEqual(1);
      expect(typeof row.hadAWayOut).toBe('boolean');
    }
    await api.dispose();
  });
});
