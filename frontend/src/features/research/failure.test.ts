import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { correctionFor, explainFailure, hasExplanation } from './failure';
import { retryPath } from './retryPath';

/**
 * Что аналитик читает, когда анализ не выполнен.
 *
 * До этой таблицы в заголовке критического уведомления стоял сырой код перечисления — «Анализ не
 * выполнен: ASSEMBLY_FAILED», — а в теле приходило исключение движка вида «ZeroDivisionError:
 * float division by zero». Это читается в момент, когда важнее всего понять, что делать дальше.
 *
 * Главная проверка здесь — не тексты, а то, что таблица не отстанет: коды заводятся в двух местах
 * (сага на Java и классификатор движка на Python), и объяснение к новому коду легко забыть. Забытое
 * объяснение возвращает ровно то состояние, ради ухода от которого таблица написана.
 */

const ROOT = resolve(__dirname, '../../../..');

function codesDeclaredIn(path: string, pattern: RegExp): string[] {
  const source = readFileSync(resolve(ROOT, path), 'utf8');
  // Группа объявлена в самом образце, но её тип — «строка или ничего»: отбрасываем пустое явно,
  // чтобы не выдать `undefined` за код и не сравнивать его с таблицей.
  return [...source.matchAll(pattern)]
    .map((match) => match[1])
    .filter((code): code is string => Boolean(code));
}

describe('объяснение отказа', () => {
  it('покрывает все коды, которые заводит сага', () => {
    // Источник правды — сам код службы, а не список, переписанный сюда руками: переписанный
    // разошёлся бы молча, и обнаружилось бы это на глазах у аналитика.
    const codes = codesDeclaredIn(
      'services/trends-service/src/main/java/dev/horizon/trends/domain/research/FailureInfo.java',
      /public static final String (\w+) =/g,
    );

    expect(codes.length).toBeGreaterThan(0);
    expect(codes.filter((code) => !hasExplanation(code))).toEqual([]);
  });

  it('покрывает все коды, которые заводит движок', () => {
    const codes = codesDeclaredIn(
      'services/analytics-service/src/horizon_analytics/application/analyze_domain.py',
      /return "([A-Z_]+)", (?:True|False)/g,
    );

    expect(codes.length).toBeGreaterThan(0);
    expect(codes.filter((code) => !hasExplanation(code))).toEqual([]);
  });

  it('каждое объяснение говорит, что произошло и что делать', () => {
    // Объяснение без следующего шага оставляет аналитика ровно там же, где сырой код: он понял, что
    // сломалось, и не понял, что ему теперь делать.
    for (const code of ['NO_DOCUMENTS_FOUND', 'ASSEMBLY_FAILED', 'SAGA_TIMEOUT']) {
      const explanation = explainFailure(code);

      expect(explanation.title.length, code).toBeGreaterThan(0);
      expect(explanation.explanation.length, code).toBeGreaterThan(0);
      expect(explanation.nextStep.length, code).toBeGreaterThan(0);
    }
  });

  it('заголовок не содержит кода перечисления', () => {
    // Именно это и чинится: «ASSEMBLY_FAILED» в заголовке не отвечает ни на один вопрос человека,
    // который в эту секунду решает, что делать дальше.
    for (const code of ['ASSEMBLY_FAILED', 'INFRASTRUCTURE_ERROR', 'НЕИЗВЕСТНЫЙ_КОД']) {
      expect(explainFailure(code).title).not.toMatch(/[A-Z]{3,}_[A-Z]/);
    }
  });

  it('незнакомый код получает честный общий ответ, а не пустоту', () => {
    // Движок вправе завести новый код, и релиз таблицы отстанет от релиза движка. Показать в этот
    // момент пустое место или сам код значило бы вернуться к тому, ради чего таблица написана.
    const explanation = explainFailure('WHAT_IS_THIS');

    expect(explanation.title).toBe('Анализ не выполнен');
    expect(explanation.nextStep).toContain('поддержку');
  });

  it('отсутствие кода не роняет разбор', () => {
    expect(explainFailure(undefined).title).toBe('Анализ не выполнен');
    expect(explainFailure('').title).toBe('Анализ не выполнен');
  });

  it('регистр и пробелы не мешают узнать код', () => {
    expect(explainFailure(' assembly_failed ').title).toBe(explainFailure('ASSEMBLY_FAILED').title);
  });
});

describe('поправка к параметрам', () => {
  /**
   * Совет, который нельзя выполнить в один щелчок, — половина совета: «расширьте окно анализа»
   * заставлял аналитика вернуться к поиску и набрать запрос заново.
   */

  it('расширяет окно ровно там, где совет обещает его расширить', () => {
    const correction = correctionFor('NO_DOCUMENTS_FOUND', { yearsWindow: 5, topN: 15 });

    expect(correction?.parameters.yearsWindow).toBe(8);
    expect(correction?.parameters.topN).toBe(15);
    expect(correction?.note).toContain('с 5 до 8');
  });

  it('не выходит за границы контракта', () => {
    // Кнопка, ведущая к отклонённому запросу, хуже отсутствия кнопки: аналитик нажимает совет
    // системы и получает отказ от неё же.
    const narrowed = correctionFor('RESOURCE_EXHAUSTED', { yearsWindow: 3, topN: 5 });

    expect(narrowed).toBeUndefined();
  });

  it('не обещает расширить окно, которое расширять некуда', () => {
    expect(correctionFor('NO_DOCUMENTS_FOUND', { yearsWindow: 15 })).toBeUndefined();
  });

  it('исходит из заданных параметров, а не из умолчаний', () => {
    // Аналитик мог сузить окно намеренно. Подставить умолчание значило бы отменить его решение и
    // не сказать об этом.
    const correction = correctionFor('ASSEMBLY_FAILED', { yearsWindow: 4 });

    expect(correction?.parameters.yearsWindow).toBe(7);
  });

  it('не выдумывает поправку там, где причина не в параметрах', () => {
    // Сбой инфраструктуры, таймаут, недоступный срез: менять параметры значит делать вид, что
    // причина известна.
    for (const code of ['INFRASTRUCTURE_ERROR', 'SAGA_TIMEOUT', 'SNAPSHOT_NOT_FOUND', 'ЧТО_ТО']) {
      expect(correctionFor(code, { yearsWindow: 7 }), code).toBeUndefined();
    }
  });

  it('называет изменение словами, а не только числами', () => {
    // Поправка показывается до запуска: молча подменённые параметры дали бы отчёт, которого
    // аналитик не просил.
    const correction = correctionFor('RESOURCE_EXHAUSTED', { yearsWindow: 10, topN: 30 });

    expect(correction?.note).toContain('сужено до 8');
    expect(correction?.note).toContain('до 25');
  });

  it('сохраняет параметры, которых поправка не касается', () => {
    const correction = correctionFor('NO_DOCUMENTS_FOUND', {
      yearsWindow: 5,
      minConfidence: 0.4,
      includeMature: true,
    });

    expect(correction?.parameters.minConfidence).toBe(0.4);
    expect(correction?.parameters.includeMature).toBe(true);
  });
});

describe('адрес повторного запуска', () => {
  it('несёт направление и заданные параметры', () => {
    const path = retryPath('квантовые вычисления', { yearsWindow: 8, topN: 15 });

    expect(path).toContain('query=');
    expect(path).toContain('yearsWindow=8');
    expect(path).toContain('topN=15');
  });

  it('не пишет в адрес то, что не задано', () => {
    // Пустое значение в строке запроса экран поиска прочитал бы как «ноль», а «не задано» и «ноль»
    // — разные вещи.
    const path = retryPath('квантовые вычисления', { yearsWindow: 8 });

    expect(path).not.toContain('topN');
    expect(path).not.toContain('minConfidence');
  });

  it('кодирует направление, а не ломает адрес', () => {
    const path = retryPath('C++ и Wi-Fi', {});

    expect(path.startsWith('/new?')).toBe(true);
    expect(new URLSearchParams(path.split('?')[1]).get('query')).toBe('C++ и Wi-Fi');
  });
});
