import { defineConfig, devices } from '@playwright/test';

/**
 * E2E configuration.
 *
 * The suite runs against a REAL stack (`make up`) whose only data source is the versioned golden
 * corpus, loaded through the ordinary `fixture` connector. That is what makes these tests both
 * genuinely end-to-end and deterministic — there is no mocking layer anywhere (ADR-0015).
 *
 * Три проекта:
 *   `api` — проверки уровня протокола против шлюза. Быстрые, устойчивые, не зависят от разметки.
 *   `web` — браузерные. Идут после `api`, чтобы отказ бэкенда сообщался понятной протокольной
 *           ошибкой, а не запутанным ожиданием в интерфейсе.
 *   `rate-limit` — последним и отдельно, потому что он **портит окружение остальным**.
 *
 * Про третий стоит сказать прямо. Входа в продукте нет, поэтому шлюз считает лимит по адресу
 * клиента, а все проверки идут с одного адреса и делят один бюджет. Проверка ограничителя
 * выбирает его до 429, и всё, что запускалось бы следом, получало бы отказ не по своей вине.
 * Вынесена в отдельный проект с зависимостью от обоих — так она по-прежнему выполняется
 * по-настоящему, но после всех.
 */
const baseURL = process.env.HORIZON_WEB_URL ?? 'http://localhost:3000';
const apiURL = process.env.HORIZON_API_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './specs',
  // Analysis of a fresh domain is allowed up to 90 s (NFR-P2); give the assertion room around it.
  timeout: 180_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'results/junit.xml' }]]
    : [['list'], ['html', { open: 'never' }]],
  outputDir: 'results/artifacts',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    extraHTTPHeaders: { 'Accept-Language': 'ru-RU,ru' },
  },
  projects: [
    {
      name: 'api',
      use: { baseURL: apiURL },
      testMatch: /.*\.api\.spec\.ts/,
    },
    {
      name: 'web',
      use: { ...devices['Desktop Chrome'], baseURL },
      testMatch: /.*\.web\.spec\.ts/,
      dependencies: ['api'],
    },
    {
      name: 'rate-limit',
      use: { baseURL: apiURL },
      testMatch: /.*\.ratelimit\.spec\.ts/,
      dependencies: ['api', 'web'],
    },
  ],
});
