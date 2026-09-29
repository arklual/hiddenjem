import { expect, request, test } from '@playwright/test';
import { API_URL } from '../support/api';

test.describe('Безопасность на границе', () => {
  test('несуществующий отчёт отвечает 404 в формате problem+json', async () => {
    const api = await request.newContext({ baseURL: API_URL });

    const response = await api.get('/api/v1/reports/00000000-0000-7000-8000-000000000000');

    expect(response.status()).toBe(404);
    expect(response.headers()['content-type']).toContain('application/problem+json');
    await api.dispose();
  });

  test('шлюз выставляет базовые заголовки безопасности', async () => {
    const api = await request.newContext({ baseURL: API_URL });

    const response = await api.get('/actuator/health');
    const headers = response.headers();

    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['x-frame-options']).toBe('DENY');
    expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
    expect(headers.server).toBeUndefined();
  });

  test('ошибки шлюза возвращаются в формате problem+json', async () => {
    const api = await request.newContext({ baseURL: API_URL });

    const response = await api.get('/api/v1/does-not-exist');

    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.headers()['content-type']).toContain('application/problem+json');
    const problem = await response.json();
    expect(problem).toHaveProperty('type');
    expect(problem).toHaveProperty('status');
  });
});
