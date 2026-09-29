import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError, PROBLEM_SLUGS, parseProblem, problemSlug } from '@/api/problem';
import { setLocale } from './i18n/locale';
import { fieldErrorMap, isNotFoundError, toUiError } from './errors';

function problemBody(overrides: Record<string, unknown> = {}) {
  return {
    type: 'https://horizon.dev/problems/validation-error',
    title: 'Ошибка валидации запроса',
    status: 400,
    detail: 'Запрос не прошёл валидацию',
    instance: '/api/v1/research-requests',
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    retryable: false,
    errors: [{ field: 'query', message: 'длина должна быть от 3 до 200' }],
    ...overrides,
  };
}

describe('the error heading follows the reader’s language', () => {
  // Сервер присылает `title` по типу проблемы, и он написан по-русски: это константы
  // `ProblemType`. Пока интерфейс предпочитал серверный заголовок, в английской локали каждая
  // ошибка выглядела так: русская шапка, английские тело и действие. Словарная проверка этого не
  // видит и не может — строка приходит по проводу.
  it('uses our own heading for a problem type this build knows', () => {
    setLocale('en');
    try {
      const ui = toUiError(new ApiError(400, parseProblem(problemBody())));

      expect(ui.title).toBe('Request validation failed');
      expect(ui.title).not.toBe('Ошибка валидации запроса');
    } finally {
      setLocale('ru');
    }
  });

  it('keeps the Russian heading in the Russian locale', () => {
    const ui = toUiError(new ApiError(400, parseProblem(problemBody())));

    expect(ui.title).toBe('Ошибка валидации запроса');
  });

  it('falls back to the server heading for a type this build does not know', () => {
    // Серверный заголовок не выброшен: для незнакомого типа он точнее общего
    // «Не удалось выполнить операцию», и потерять его значило бы лечить одно другим.
    const unknown = parseProblem(
      problemBody({ type: 'https://horizon.dev/problems/brand-new-thing', title: 'Нечто новое' }),
    );

    expect(toUiError(new ApiError(400, unknown)).title).toBe('Нечто новое');
  });

  it('has a heading for every problem type the catalogue knows', () => {
    // Канарейка полноты: заголовок, потерянный для одного типа, вернул бы ровно ту же болезнь на
    // одном экране, и заметить её было бы нечем.
    setLocale('en');
    try {
      for (const slug of PROBLEM_SLUGS) {
        const ui = toUiError(
          new ApiError(
            400,
            parseProblem(problemBody({ type: `https://horizon.dev/problems/${slug}` })),
          ),
        );

        expect(ui.title, slug).not.toMatch(/[а-яё]/i);
      }
    } finally {
      setLocale('ru');
    }
  });
});

describe('problem+json parsing', () => {
  it('extracts the slug from the type URI', () => {
    expect(problemSlug('https://horizon.dev/problems/quota-exceeded')).toBe('quota-exceeded');
  });

  it('returns null for a type the frontend does not know', () => {
    // An unknown slug must degrade to generic handling rather than crash: services are allowed
    // to add problem types without a coordinated frontend release.
    expect(problemSlug('https://horizon.dev/problems/brand-new-thing')).toBeNull();
    expect(problemSlug(undefined)).toBeNull();
  });

  it('rejects a body that is not a problem document', () => {
    expect(parseProblem({ message: 'boom' })).toBeNull();
    expect(parseProblem('not json')).toBeNull();
    expect(parseProblem(null)).toBeNull();
  });

  it('parses a well-formed problem document', () => {
    const problem = parseProblem(problemBody());

    expect(problem?.status).toBe(400);
    expect(problem?.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(problem?.errors?.[0]?.field).toBe('query');
  });
});

describe('toUiError', () => {
  it('turns a validation problem into user-facing copy with field errors', () => {
    const error = new ApiError(400, parseProblem(problemBody()));

    const ui = toUiError(error);

    expect(ui.kind).toBe('api');
    expect(ui.status).toBe(400);
    expect(ui.slug).toBe('validation-error');
    // The service authors its own Russian title; the frontend must not overwrite it.
    expect(ui.title).toBe('Ошибка валидации запроса');
    expect(ui.message).not.toBe('');
    expect(ui.action).not.toBe('');
    expect(ui.fieldErrors).toHaveLength(1);
    expect(ui.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(ui.retryable).toBe(false);
  });

  it('marks 5xx and 429 retryable even when the service did not say so', () => {
    const serverError = toUiError(new ApiError(503, null));
    const throttled = toUiError(new ApiError(429, null, { retryAfterSeconds: 60 }));

    expect(serverError.retryable).toBe(true);
    expect(throttled.retryable).toBe(true);
    expect(throttled.retryAfterSeconds).toBe(60);
  });

  it('never marks a 400 retryable — repeating an invalid request cannot help', () => {
    expect(toUiError(new ApiError(400, parseProblem(problemBody()))).retryable).toBe(false);
  });

  it('maps a transport failure to a network error with a retry action', () => {
    const ui = toUiError(new NetworkError('offline'));

    expect(ui.kind).toBe('network');
    expect(ui.status).toBeNull();
    expect(ui.retryable).toBe(true);
  });

  it('falls back to a safe message for an unexpected value', () => {
    // Anything can reach an error boundary — a string, undefined, a rejected non-Error.
    const ui = toUiError('something odd');

    expect(ui.kind).toBe('unknown');
    expect(ui.title).not.toBe('');
    expect(ui.message).not.toBe('');
  });
});

describe('error predicates', () => {
  it('recognises absence', () => {
    expect(isNotFoundError(new ApiError(404, null))).toBe(true);

    expect(isNotFoundError(new ApiError(500, null))).toBe(false);
    expect(isNotFoundError(new NetworkError('x'))).toBe(false);
  });

  it('builds a field → message map for form binding', () => {
    const map = fieldErrorMap(new ApiError(400, parseProblem(problemBody())));

    expect(map).toEqual({ query: 'длина должна быть от 3 до 200' });
    expect(fieldErrorMap(new NetworkError('x'))).toEqual({});
  });
});
