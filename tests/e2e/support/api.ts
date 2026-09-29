import { APIRequestContext, expect, request } from '@playwright/test';

export const API_URL = process.env.HORIZON_API_URL ?? 'http://localhost:8080';

/** A domain that the golden corpus is guaranteed to have material for. */
export const DEMO_DOMAIN = 'технологии в искусственном интеллекте';

/**
 * Направление на языке корпуса — английское.
 *
 * Нужно там, где проверяется не понимание формулировки, а сам путь: сага, состав отчёта,
 * прослеживаемость темы до источника. Эти правила от языка запроса не зависят, и держать их на
 * русском направлении значило бы проверять две вещи сразу, а при отказе гадать, какая из них.
 *
 * Понимание русской формулировки проверяется отдельно — `direction-recognition.api.spec.ts`.
 */
export const CORPUS_DOMAIN = 'artificial intelligence';

/**
 * Контекст запросов к публичному API.
 *
 * Входа в продукте нет: организация одна, `/api/v1/**` открыт, и запросы идут без заголовка
 * Authorization. Отдельный контекст на набор нужен лишь затем, чтобы закрывать его в `afterAll` и
 * не делить куки и соединения между файлами.
 */
export async function apiContext(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: API_URL });
}

/**
 * Ключ идемпотентности из произвольного направления: ASCII, не длиннее 80 символов контракта.
 */
function idempotencyKey(query: string): string {
  const encoded = Buffer.from(query, 'utf8').toString('base64url').slice(0, 48);
  return `e2e-${encoded}-${Date.now()}`;
}

/**
 * Submits an analysis and waits for a terminal state.
 *
 * Polls the status endpoint rather than the SSE stream: the API suite verifies the authoritative
 * state machine, and the SSE transport is covered separately by the web suite.
 */
export async function runAnalysis(
  api: APIRequestContext,
  query: string,
  parameters: Record<string, unknown> = {},
  timeoutMs = 150_000,
  /** Считать заново, не переиспользуя готовый отчёт: единственный способ получить вторую версию. */
  refresh = false,
): Promise<{ requestId: string; reportId: string; status: string; partial: boolean }> {
  const submit = await api.post('/api/v1/research-requests', {
    data: { query, parameters, ...(refresh ? { refresh: true } : {}) },
    // Ключ обязан быть ASCII: значение HTTP-заголовка другого алфавита не допускает, и Playwright
    // отказывался отправлять запрос с «Invalid character in header content». Направления в тестах
    // русские, поэтому запрос кодируется, а не подставляется как есть. Длина ограничена 80
    // символами контрактом — отсюда усечение кодированной части, уникальность даёт время.
    headers: { 'Idempotency-Key': idempotencyKey(query) },
  });
  expect([200, 202], await safeBody(submit)).toContain(submit.status());
  const created = await submit.json();
  const requestId: string = created.id;

  const deadline = Date.now() + timeoutMs;
  let last = created;
  while (Date.now() < deadline) {
    const statusResponse = await api.get(`/api/v1/research-requests/${requestId}`);
    expect(statusResponse.ok(), await safeBody(statusResponse)).toBeTruthy();
    last = await statusResponse.json();
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(last.status)) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }

  expect(last.status, `analysis did not complete: ${JSON.stringify(last)}`).toBe('COMPLETED');
  expect(last.reportId).toBeTruthy();
  return { requestId, reportId: last.reportId, status: last.status, partial: !!last.partial };
}

export async function safeBody(response: { text: () => Promise<string> }): Promise<string> {
  try {
    return await response.text();
  } catch {
    return '<no body>';
  }
}
