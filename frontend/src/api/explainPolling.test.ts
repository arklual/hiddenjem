import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApi } from './endpoints';
import type { HttpClient, RequestOptions } from './http';

const RUNNING = { state: 'RUNNING', retryAfterSeconds: 3 };
const ANSWER = { stages: ['extracted', 'ranked'], traces: [] };

/** Клиент, который отдаёт заготовленные тела по очереди и прогоняет их через схему вызова. */
function scripted(bodies: unknown[]): { http: HttpClient; calls: string[] } {
  const calls: string[] = [];
  const http: HttpClient = {
    request<TParsed>(path: string, options?: RequestOptions<TParsed>): Promise<TParsed> {
      calls.push(path);
      const body = bodies.shift();
      return Promise.resolve(options?.schema ? options.schema.parse(body) : (body as TParsed));
    },
    requestBlob: () => Promise.reject(new Error('не используется')),
  };
  return { http, calls };
}

describe('трассировка термина опрашивается, а не ждёт одним запросом', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('повторяет тот же запрос, пока движок считает, и возвращает готовый ответ', async () => {
    vi.useFakeTimers();
    const { http, calls } = scripted([RUNNING, RUNNING, ANSWER]);

    const answer = createApi(http).explainReportTerms('r-1', ['firewall']);
    await vi.advanceTimersByTimeAsync(6_000);

    await expect(answer).resolves.toEqual(ANSWER);
    expect(calls).toEqual(Array(3).fill('/api/v1/reports/r-1/explain?term=firewall'));
  });

  it('прекращает опрос, когда экран закрыт', async () => {
    vi.useFakeTimers();
    const { http, calls } = scripted([RUNNING, RUNNING]);
    const controller = new AbortController();

    const answer = createApi(http).explainReportTerms('r-1', ['firewall'], controller.signal);
    const settled = expect(answer).rejects.toMatchObject({ name: 'AbortError' });
    await vi.advanceTimersByTimeAsync(1_000);
    controller.abort();
    await settled;
    await vi.advanceTimersByTimeAsync(10_000);

    expect(calls).toHaveLength(1);
  });
});
