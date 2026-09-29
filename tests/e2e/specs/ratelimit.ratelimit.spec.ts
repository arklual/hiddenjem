import { expect, request, test } from '@playwright/test';
import { API_URL } from '../support/api';

/**
 * Ограничитель запросов на границе (NFR-S7).
 *
 * Входа в продукте нет, поэтому лимит считается по адресу клиента, и все проверки набора делят
 * один бюджет. Проверка выбирает его до конца, а значит всё, что шло бы после неё в пределах
 * минуты, получало бы 429 не по своей вине. Поэтому она в отдельном проекте Playwright
 * `rate-limit`, объявленном зависимым от `api` и `web`, — выполняется последней.
 *
 * Запросы идут в дешёвый открытый эндпоинт квоты: проверяется шлюз, а не стоимость анализа.
 * Потолок в CI поднят (RATE_LIMIT_PER_MINUTE, RATE_LIMIT_BURST в ci.yml), но остаётся достижимым
 * за отведённое число попыток.
 */
const MAX_ATTEMPTS = 1_000;

test.describe('Ограничитель запросов', () => {
  test('превышение лимита запросов с одного адреса возвращает 429 с Retry-After', async () => {
    const api = await request.newContext({ baseURL: API_URL });

    let limited: { status: number; retryAfter?: string } | null = null;
    for (let i = 0; i < MAX_ATTEMPTS; i++) {
      const response = await api.get('/api/v1/research-requests/quota');
      if (response.status() === 429) {
        limited = { status: 429, retryAfter: response.headers()['retry-after'] };
        expect(response.headers()['content-type']).toContain('application/problem+json');
        break;
      }
      // До срабатывания ограничителя запрос обязан проходить: иначе 429 ниже ничего бы не доказывал.
      expect(response.status(), `попытка ${i + 1}`).toBe(200);
    }

    expect(limited, `ограничитель запросов не сработал за ${MAX_ATTEMPTS} попыток`).not.toBeNull();
    expect(limited?.retryAfter).toBeTruthy();
    await api.dispose();
  });
});
