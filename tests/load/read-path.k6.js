/**
 * Load profile for the read path (NFR-P1: p95 ≤ 300 ms, p99 ≤ 800 ms at 50 RPS).
 *
 * Only the read path is load-tested at this intensity, deliberately: analysis is an asynchronous,
 * CPU-bound pipeline whose capacity is governed by consumer parallelism, not by HTTP concurrency,
 * and hammering it with 50 RPS would measure queue depth rather than latency. Analysis throughput is
 * covered by a separate, lower-rate scenario below.
 *
 * Run:  k6 run tests/load/read-path.k6.js
 * Env:  BASE_URL, REPORT_ID (optional — otherwise one is produced first)
 *
 * Входа в продукте нет: публичный API открыт, запросы идут без заголовка Authorization.
 */
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Направление английское, и это не небрежность. Эталонный корпус, на котором поднят стенд, —
// англоязычный, а сопоставление в коннекторах идёт по словам запроса. Русская формулировка
// заканчивается «не найдено ни одного документа» за секунду: разогрев падал с NO_DOCUMENTS_FOUND,
// и нагрузочный прогон не измерял ничего — все три метрики выходили нулевыми при зелёных порогах.
// Разбор самого пробела — п. 22 бэклога; здесь измеряется стоимость пути чтения, а не понимание
// формулировки.
const DOMAIN_QUERY = __ENV.DOMAIN_QUERY || 'artificial intelligence';

const reportLatency = new Trend('horizon_report_latency', true);
const analysisLatency = new Trend('horizon_analysis_total', true);
const errorRate = new Rate('horizon_errors');

/*
 * Нагрузка и пороги задаются переменными, и вот почему.
 *
 * Умолчания — это SLO из docs/01-analysis/02-non-functional-requirements.md на целевом железе: 50
 * запросов в секунду и p(95) чтения отчёта меньше 300 мс. Ночной прогон идёт не на целевом железе,
 * а на раннере с двумя ядрами, где в тех же двух ядрах живут Postgres, Redis, redpanda, ClickHouse,
 * четыре JVM-сервиса, Prometheus, Grafana — и сам k6. Первое же измерение это и показало: при 40
 * запросах в секунду медиана чтения 2,8 с при минимуме 115 мс, то есть очередь, а не регрессия.
 *
 * Оставить так — значит держать вечно красное задание, которое, по собственному правилу ночного
 * прогона, команда быстро научится не читать. Ослабить SLO в требованиях — значит подогнать
 * требование под тесную машину. Поэтому цифры разведены: сценарий один, а масштаб и пороги задаёт
 * тот, кто запускает. CI следит за деградацией на своём масштабе; SLO проверяется на стенде тем же
 * файлом без переменных.
 */
const RATE = Number(__ENV.LOAD_RATE || 50);
const THRESHOLD_REPORT_P95 = __ENV.THRESHOLD_REPORT_P95 || 300;
const THRESHOLD_REPORT_P99 = __ENV.THRESHOLD_REPORT_P99 || 800;
const THRESHOLD_ANALYSIS_P95 = __ENV.THRESHOLD_ANALYSIS_P95 || 90000;

export const options = {
  scenarios: {
    read_path: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 30,
      maxVUs: 120,
      exec: 'readReport',
    },
    analysis_path: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1m',
      duration: '3m',
      preAllocatedVUs: 4,
      maxVUs: 8,
      exec: 'runAnalysis',
      startTime: '10s',
    },
  },
  thresholds: {
    // Умолчания — SLO. Доля ошибок переменной не имеет намеренно: она от мощности машины не
    // зависит, и запрос, ответивший ошибкой, ошибочен на любом железе.
    'horizon_report_latency': [
      `p(95)<${THRESHOLD_REPORT_P95}`,
      `p(99)<${THRESHOLD_REPORT_P99}`,
    ],
    'horizon_analysis_total': [`p(95)<${THRESHOLD_ANALYSIS_P95}`],
    'horizon_errors': ['rate<0.01'],
    'http_req_failed': ['rate<0.01'],
  },
};

export function setup() {
  const json = { headers: { 'Content-Type': 'application/json' } };

  let reportId = __ENV.REPORT_ID;
  if (!reportId) {
    const submit = http.post(
      `${BASE_URL}/api/v1/research-requests`,
      JSON.stringify({ query: DOMAIN_QUERY, parameters: { topN: 15, yearsWindow: 7 } }),
      json,
    );
    const requestId = submit.json('id');
    const deadline = Date.now() + 180_000;
    while (Date.now() < deadline) {
      const status = http.get(`${BASE_URL}/api/v1/research-requests/${requestId}`);
      const body = status.json();
      if (body.status === 'COMPLETED') {
        reportId = body.reportId;
        break;
      }
      if (body.status === 'FAILED' || body.status === 'CANCELLED') {
        fail(`warm-up analysis ${body.status}: ${JSON.stringify(body.failure)}`);
      }
      sleep(2);
    }
  }
  if (!reportId) {
    fail('could not obtain a report to load-test against');
  }
  return { reportId };
}

export function readReport(data) {
  const report = http.get(`${BASE_URL}/api/v1/reports/${data.reportId}`, {
    tags: { name: 'GET /reports/{id}' },
  });
  reportLatency.add(report.timings.duration);
  const reportOk = check(report, {
    'report 200': (r) => r.status === 200,
    'report has trends': (r) => (r.json('trends') || []).length > 0,
  });

  errorRate.add(!reportOk);
}

export function runAnalysis() {
  const json = { headers: { 'Content-Type': 'application/json' } };
  // A unique query per iteration so the freshness cache does not mask real pipeline cost.
  const query = `${DOMAIN_QUERY} ${__VU}-${__ITER}`;
  const started = Date.now();

  const submit = http.post(
    `${BASE_URL}/api/v1/research-requests`,
    JSON.stringify({ query, parameters: { topN: 15, yearsWindow: 7 } }),
    json,
  );
  if (!check(submit, { 'submit accepted': (r) => r.status === 202 || r.status === 200 })) {
    errorRate.add(true);
    return;
  }

  const requestId = submit.json('id');
  const deadline = Date.now() + 150_000;
  let finalStatus = 'TIMEOUT';
  while (Date.now() < deadline) {
    const status = http.get(`${BASE_URL}/api/v1/research-requests/${requestId}`);
    finalStatus = status.json('status');
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(finalStatus)) {
      break;
    }
    sleep(3);
  }

  analysisLatency.add(Date.now() - started);
  errorRate.add(finalStatus !== 'COMPLETED');
  check(null, { 'analysis completed': () => finalStatus === 'COMPLETED' });
}
