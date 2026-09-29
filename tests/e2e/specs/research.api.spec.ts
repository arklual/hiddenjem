import { APIRequestContext, expect, test } from '@playwright/test';
import { CORPUS_DOMAIN, apiContext, runAnalysis } from '../support/api';

/**
 * The core product journey, verified at protocol level.
 *
 * These assertions encode business rules, not implementation details: every trend must be
 * traceable to a source (BR-A6), the score must decompose into indicators whose multipliers
 * reproduce it (BR-B3), and the methodology metadata must be present (BR-B6).
 */
test.describe('Анализ технологического направления', () => {
  let api: APIRequestContext;
  let reportId: string;

  test.beforeAll(async () => {
    api = await apiContext();
    // Направление английское намеренно: здесь проверяется путь и состав отчёта, а не понимание
    // русской формулировки — она проверяется отдельными тестами.
    const run = await runAnalysis(api, CORPUS_DOMAIN, { topN: 15, yearsWindow: 7 });
    reportId = run.reportId;
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test('отчёт содержит запрошенное число трендов в корректном порядке', async () => {
    const response = await api.get(`/api/v1/reports/${reportId}`);
    expect(response.status()).toBe(200);
    const report = await response.json();

    expect(report.trends.length).toBeGreaterThan(0);
    expect(report.trends.length).toBeLessThanOrEqual(15);

    const ranks = report.trends.map((t: { rank: number }) => t.rank);
    expect(ranks).toEqual([...Array(ranks.length).keys()].map((i) => i + 1));

    const scores = report.trends.map((t: { assessment: { score: number } }) => t.assessment.score);
    const sorted = [...scores].sort((a, b) => b - a);
    expect(scores).toEqual(sorted);
  });

  test('каждый тренд трассируется до первоисточника (BR-A5, BR-A6)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      expect(trend.evidence.length, `тренд «${trend.title}» без источников`).toBeGreaterThan(0);
      for (const evidence of trend.evidence) {
        expect(evidence.url).toMatch(/^https?:\/\//);
        expect(evidence.publishedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        expect(evidence.sourceClass).toBeTruthy();
      }
      // Case example and motivation must point at evidence that actually exists.
      if (trend.caseExample) {
        expect(trend.caseExample.evidenceIndex).toBeLessThan(trend.evidence.length);
      }
      for (const attribution of trend.motivation.attributions ?? []) {
        expect(attribution.evidenceIndex).toBeLessThan(trend.evidence.length);
      }
    }
  });

  test('мотивация содержит и проблему, и преимущество (BR-A3)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      expect(trend.motivation.problem.trim().length).toBeGreaterThan(10);
      expect(trend.motivation.benefit.trim().length).toBeGreaterThan(10);
    }
  });

  test('балл раскладывается на индикаторы, произведение множителей воспроизводит балл (BR-B3)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      const indicators = trend.assessment.indicators;
      expect(indicators.length).toBeGreaterThanOrEqual(6);

      const weightSum = indicators.reduce((acc: number, i: { weight: number }) => acc + i.weight, 0);
      expect(weightSum).toBeCloseTo(1, 6);

      const product = indicators.reduce((acc: number, i: { multiplier: number }) => acc * i.multiplier, 1);
      expect(product * 100).toBeCloseTo(trend.assessment.score, 3);

      for (const indicator of indicators) {
        expect(indicator.value).toBeGreaterThanOrEqual(0);
        expect(indicator.value).toBeLessThanOrEqual(1);
        expect(indicator.explanation ?? '').not.toBe('');
      }
    }
  });

  test('карта слабых сигналов: индикатор роста несёт координаты DoV (Yoon 2012)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      const growth = trend.assessment.indicators.find((i: { name: string }) => i.name === 'growth');
      expect(growth, 'у каждого тренда есть индикатор growth').toBeTruthy();
      // The weak-signal map's axes come from these; without them the client silently falls back
      // to recomputing from the timeline, so their presence is part of the contract in practice.
      expect(growth.diagnostics).toHaveProperty('dovMean');
      expect(growth.diagnostics).toHaveProperty('dovGrowthRate');
      expect(typeof growth.diagnostics.weakSignalQuadrant).toBe('boolean');
    }
  });

  test('методология и снапшот корпуса зафиксированы в отчёте (BR-B6)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    expect(report.methodologyVersion).toMatch(/^em-\d+\.\d+\.\d+$/);
    expect(report.corpusSnapshotId).toBeTruthy();
    expect(report.methodologyProfileId).toBeTruthy();
    expect(report.coverage.documentsAnalyzed).toBeGreaterThan(0);
  });

  test('таймлайн и год первого упоминания согласованы (BR-B1, BR-B2)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      expect(trend.timeline.length).toBeGreaterThan(0);
      const years = trend.timeline.map((p: { period: string }) => Number(p.period.slice(0, 4)));
      expect(trend.firstMentionYear).toBeGreaterThanOrEqual(Math.min(...years) - 1);
      const totalFromTimeline = trend.timeline.reduce(
        (acc: number, p: { documentCount: number }) => acc + p.documentCount,
        0,
      );
      expect(totalFromTimeline).toBeGreaterThan(0);
    }
  });

  test('низкая уверенность помечена явно (BRULE-6)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    for (const trend of report.trends) {
      const { confidence, lowEvidence } = trend.assessment;
      expect(confidence).toBeGreaterThanOrEqual(0);
      expect(confidence).toBeLessThanOrEqual(1);
      if (confidence < 0.4) {
        expect(lowEvidence, `тренд «${trend.title}» с confidence ${confidence} не помечен`).toBe(true);
      }
    }
  });

  test('отчёт называет движок и режим, которыми посчитан', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();

    // Движок один; `methodology` осталось только подписью отчётов, выпущенных до его вывода.
    expect(report.engine).toBe('signals');
    // Режим не передан — значит быстрый, в срок ТЗ.
    expect(report.mode).toBe('fast');
  });

  test('незнакомый режим отвергается с перечнем известных, а не считается быстрым', async () => {
    const response = await api.post('/api/v1/research-requests', {
      data: { query: `режим ${Date.now()}`, parameters: { mode: 'slow' } },
    });

    expect(response.status()).toBe(400);
    const problem = await response.json();
    expect(problem.detail).toContain('fast');
    expect(problem.detail).toContain('quality');
    // Что быстрый и качественный ответы — разные вопросы, проверяет модульный тест ключа
    // параметров: здесь это стоило бы настоящего сорокаминутного анализа и квоты соседних сценариев.
  });

  test('экспорт доступен в JSON и CSV (BR-E4)', async () => {
    const json = await api.get(`/api/v1/reports/${reportId}/export?format=json`);
    expect(json.status()).toBe(200);
    expect((await json.json()).trends.length).toBeGreaterThan(0);

    const csv = await api.get(`/api/v1/reports/${reportId}/export?format=csv`);
    expect(csv.status()).toBe(200);
    const text = await csv.text();
    // Файл открывается портретом направления, затем пустая строка, затем таблица. Оговорка,
    // помещённая под пятнадцать строк, спрятана — а именно CSV уносят в комитет.
    const lines = text.split('\n');
    const caveat = lines.findIndex((line) => line.startsWith('тем на тонкой доказательной базе'));
    const header = lines.findIndex((line) => line.startsWith('rank;'));
    expect(caveat).toBeGreaterThanOrEqual(0);
    expect(header).toBeGreaterThan(caveat);
    expect(lines[header]).toContain('emergence_score');
    expect(lines.length).toBeGreaterThan(header + 1);
  });

  test('повторный запрос того же направления переиспользует готовый результат (BR-A8)', async () => {
    const started = Date.now();
    const response = await api.post('/api/v1/research-requests', {
      data: { query: CORPUS_DOMAIN, parameters: { topN: 15, yearsWindow: 7 } },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.fromCache).toBe(true);
    expect(body.reportId).toBeTruthy();
    expect(Date.now() - started).toBeLessThan(5_000);
  });

  test('обратная связь сохраняется и возвращается при повторном чтении (BR-E5)', async () => {
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();
    const trendKey = report.trends[0].trendKey;

    const put = await api.put(
      `/api/v1/reports/${reportId}/trends/${encodeURIComponent(trendKey)}/feedback`,
      { data: { verdict: 'RELEVANT', comment: 'e2e' } },
    );
    expect(put.status()).toBe(200);

    const again = await api.get(`/api/v1/reports/${reportId}/trends/${encodeURIComponent(trendKey)}`);
    expect(again.status()).toBe(200);
    expect((await again.json()).feedback?.verdict).toBe('RELEVANT');
  });

  test('пакетный пересчёт отвечает построчно, а не отказом (UC-14)', async () => {
    const response = await api.post('/api/v1/saved-domains/refresh');
    expect(response.status()).toBe(200);
    const rows = await response.json();

    // 200 даже если не поставлено ни одно направление: три исхода из четырёх не являются отказом,
    // а HTTP-ошибка заставила бы гадать, что произошло с каждым.
    expect(Array.isArray(rows)).toBe(true);
    for (const row of rows) {
      expect(['accepted', 'already-running', 'reused', 'quota-exceeded', 'failed']).toContain(
        row.outcome,
      );
      // Отказ обязан назвать причину, успех — идентификатор запроса, за которым можно следить.
      if (['accepted', 'already-running', 'reused'].includes(row.outcome)) {
        expect(row.requestId).toBeTruthy();
      } else {
        expect(row.reason).toBeTruthy();
      }
    }
  });

  test('второе нажатие по идущему направлению не создаёт второй анализ (BR-A24, BR-A26)', async () => {
    // Направление, которого ещё не было: только так первый запрос действительно уходит в работу.
    const query = `квантовая криптография ${Date.now()}`;
    const first = await api.post('/api/v1/research-requests', {
      data: { query, parameters: { topN: 15, yearsWindow: 7 } },
    });
    expect(first.status()).toBe(202);
    const firstBody = await first.json();
    expect(firstBody.outcome).toBe('accepted');
    const firstId = firstBody.id;
    expect(firstId).toBeTruthy();

    const second = await api.post('/api/v1/research-requests', {
      data: { query, parameters: { topN: 15, yearsWindow: 7 } },
    });

    // 200, а не 202: новой работы не начато. Какой именно исход — зависит от того, успел ли анализ
    // завершиться между двумя вызовами, но оба допустимых исхода названы явно, и оба обязаны
    // вернуть тот же самый запрос: второго не появилось.
    expect(second.status()).toBe(200);
    const body = await second.json();
    expect(['already-running', 'reused']).toContain(body.outcome);
    expect(body.fromCache).toBe(true);
    expect(body.id).toBe(firstId);
  });

  test('пересечение направлений считается по составу, а не по баллам (UC-15)', async () => {
    const response = await api.get('/api/v1/saved-domains/overlap');
    expect(response.status()).toBe(200);
    const overlap = await response.json();

    // Числа приходят всегда: «сравнивать не с чем» и «пересечений нет» дают одинаково пустой
    // список, и различить их можно только по ним.
    expect(typeof overlap.directionsCompared).toBe('number');
    expect(typeof overlap.directionsSkipped).toBe('number');
    expect(Array.isArray(overlap.topics)).toBe(true);

    for (const topic of overlap.topics) {
      // Тема, встретившаяся в одном направлении, — не пересечение, и в списке ей не место.
      expect(topic.appearances.length).toBeGreaterThanOrEqual(2);
      expect(typeof topic.trendKey).toBe('string');

      // Одно направление даёт теме не более одного вхождения: иначе «в трёх направлениях»
      // означало бы «в двух, одно из них дважды».
      const directions = topic.appearances.map((a: { savedDomainId: string }) => a.savedDomainId);
      expect(new Set(directions).size).toBe(directions.length);

      for (const appearance of topic.appearances) {
        expect(appearance.rank).toBeGreaterThan(0);
        expect(appearance.reportId).toBeTruthy();
        // §12 методологии: балл нормирован внутри своего корпуса, у разных направлений общей шкалы
        // нет. Показанное здесь число было бы сравнено читателем независимо от подписи.
        expect(appearance.score).toBeUndefined();
      }
    }
  });

  test('оценка аналитика переживает пересчёт направления (BR-A32, BR-A33)', async () => {
    // Второй отчёт по тому же направлению: параметры другие, поле то же. Именно так и получается
    // вторая версия — проверка свежести вернула бы первую, если бы вопрос был буквально тем же.
    const other = await runAnalysis(api, CORPUS_DOMAIN, { topN: 10, yearsWindow: 5 });
    expect(other.reportId).not.toBe(reportId);

    const first = await (await api.get(`/api/v1/reports/${reportId}`)).json();
    const secondReport = await (await api.get(`/api/v1/reports/${other.reportId}`)).json();
    const inBoth = new Set(
      secondReport.trends.map((trend: { trendKey: string }) => trend.trendKey),
    );
    const marked = first.trends
      .map((trend: { trendKey: string }) => trend.trendKey)
      .find((key: string) => inBoth.has(key));
    expect(marked, 'у двух отчётов по одному направлению должна быть хотя бы одна общая тема').toBeTruthy();
    const put = await api.put(
      `/api/v1/reports/${reportId}/trends/${encodeURIComponent(marked)}/feedback`,
      { data: { verdict: 'NOISE', comment: 'разобрано в прошлой версии' } },
    );
    expect(put.status()).toBe(200);
    // Только что поставленная оценка — своя, а не перенесённая.
    expect((await put.json()).carried ?? false).toBe(false);

    const second = await (await api.get(`/api/v1/reports/${other.reportId}`)).json();
    const sameTopic = second.trends.find(
      (trend: { trendKey: string }) => trend.trendKey === marked,
    );

    // Безусловно: тема выбрана как общая для обоих отчётов до того, как её пометили, поэтому
    // «её здесь нет» — это провал переноса, а не законный исход. Условная проверка прошла бы
    // вхолостую ровно в том случае, ради которого написана.
    expect(sameTopic, `тема ${marked} должна быть в обоих отчётах`).toBeTruthy();
    expect(sameTopic.feedback?.verdict).toBe('NOISE');
    expect(sameTopic.feedback?.comment).toBe('разобрано в прошлой версии');
    expect(sameTopic.feedback?.carried).toBe(true);

    // Подтверждение делает перенесённую оценку здешней.
    const confirm = await api.put(
      `/api/v1/reports/${other.reportId}/trends/${encodeURIComponent(marked)}/feedback`,
      { data: { verdict: 'RELEVANT' } },
    );
    expect(confirm.status()).toBe(200);
    const reread = await api.get(
      `/api/v1/reports/${other.reportId}/trends/${encodeURIComponent(marked)}`,
    );
    expect(reread.status()).toBe(200);
    const trend = await reread.json();
    expect(trend.feedback?.verdict).toBe('RELEVANT');
    expect(trend.feedback?.carried ?? false).toBe(false);
  });

  test('экспорт не выносит наружу личную разметку аналитика (BR-A34)', async () => {
    // Файл с Content-Disposition передают дальше. Вердикты и дословные комментарии аналитика туда
    // попадать не должны, а неизменяемый отчёт не может выгружаться разными байтами у разных людей.
    const report = await (await api.get(`/api/v1/reports/${reportId}`)).json();
    const key = report.trends[0].trendKey;
    const put = await api.put(
      `/api/v1/reports/${reportId}/trends/${encodeURIComponent(key)}/feedback`,
      { data: { verdict: 'NOISE', comment: 'секретная заметка для экспорта' } },
    );
    expect(put.status()).toBe(200);

    const exported = await api.get(`/api/v1/reports/${reportId}/export?format=json`);
    expect(exported.status()).toBe(200);
    const body = await exported.text();

    expect(body).not.toContain('секретная заметка для экспорта');
    for (const trend of JSON.parse(body).trends) {
      expect(trend.feedback).toBeUndefined();
    }
  });

  test('принудительный пересчёт создаёт новую версию направления (BR-A37, BR-A39)', async () => {
    // Без флага повтор вернул бы готовый отчёт: TTL по умолчанию сутки, и вторая версия направления
    // не появилась бы никогда — вместе с дельтой, «вошло/выбыло» и осью движения карты.
    const reused = await api.post('/api/v1/research-requests', {
      data: { query: CORPUS_DOMAIN, parameters: { topN: 15, yearsWindow: 7 } },
    });
    expect(reused.status()).toBe(200);
    expect((await reused.json()).outcome).toBe('reused');

    const forced = await runAnalysis(api, CORPUS_DOMAIN, { topN: 15, yearsWindow: 7 }, 150_000, true);
    expect(forced.reportId).not.toBe(reportId);

    const second = await (await api.get(`/api/v1/reports/${forced.reportId}`)).json();
    expect(second.version).toBeGreaterThan(1);
    expect(second.previousVersionId, 'новый отчёт обязан ссылаться на предыдущий').toBeTruthy();

    // И только теперь дельта может ответить по существу — до этого она отвечала «сравнивать не с
    // чем» при любом состоянии системы.
    const delta = await api.get(`/api/v1/reports/${forced.reportId}/delta`);
    expect(delta.status()).toBe(200);
    const body = await delta.json();
    expect(body.unavailableReason ?? null).toBeNull();
    expect(body.entered.length + body.left.length + body.stayed.length).toBeGreaterThan(0);
  });

  test('остаток бюджета приходит до того, как его потратят (BR-A49)', async () => {
    const response = await api.get('/api/v1/research-requests/quota');
    expect(response.status()).toBe(200);
    const quota = await response.json();

    expect(typeof quota.known).toBe('boolean');
    if (quota.known) {
      // Меньший из двух — тот, что остановит аналитика первым.
      expect(quota.remaining).toBe(Math.min(quota.userRemaining, quota.organizationRemaining));
      expect(quota.userRemaining).toBeLessThanOrEqual(quota.userLimit);
      expect(quota.organizationRemaining).toBeLessThanOrEqual(quota.organizationLimit);
      expect(quota.remaining).toBeGreaterThanOrEqual(0);
    } else {
      // «Неизвестно» — это отсутствие чисел, а не нули: квота fail-open, и сказать «бюджета нет»
      // значило бы утверждать обратное тому, что делает система.
      expect(quota.remaining).toBeUndefined();
    }
  });

  test('сводка радара приходит одним запросом (UC-13)', async () => {
    const response = await api.get('/api/v1/saved-domains/digest');
    expect(response.status()).toBe(200);
    const digest = await response.json();

    // Массив, пусть и пустой: список отслеживаемого — это ответ, а не ошибка.
    expect(Array.isArray(digest)).toBe(true);
    for (const row of digest) {
      expect(typeof row.query).toBe('string');
      expect(typeof row.entered).toBe('number');
      expect(typeof row.left).toBe('number');
      expect(Array.isArray(row.headline)).toBe(true);
    }
  });

  test('портрет направления считается из самого отчёта (BR-A16, BR-A17)', async () => {
    const response = await api.get(`/api/v1/reports/${reportId}`);
    expect(response.status()).toBe(200);
    const report = await response.json();
    const portrait = report.portrait;

    expect(portrait).toBeTruthy();
    // Каждая величина должна сходиться с тем, что лежит в самом отчёте: сводка, расходящаяся с
    // данными под ней, хуже её отсутствия — по ней будут отвечать перед комитетом.
    expect(portrait.trendsInReport).toBe(report.trends.length);
    expect(portrait.lowEvidenceCount).toBe(
      report.trends.filter((t: { assessment: { lowEvidence?: boolean } }) => t.assessment.lowEvidence)
        .length,
    );
    const counted = portrait.byLifecycleStage.reduce(
      (sum: number, s: { count: number }) => sum + s.count,
      0,
    );
    expect(counted).toBe(portrait.trendsInReport);

    // Оговорки — обязательные поля, а не приложение (BR-A17).
    expect(Array.isArray(portrait.unavailableSources)).toBe(true);
    expect(typeof portrait.lowEvidenceCount).toBe('number');
  });

  test('дельта отчёта отвечает причиной, а не пустотой (BR-A15)', async () => {
    const response = await api.get(`/api/v1/reports/${reportId}/delta`);
    expect(response.status()).toBe(200);
    const delta = await response.json();

    // The three groups are always present so a client never has to guess whether an absent field
    // means "empty" or "not computed" — that is what `unavailableReason` is for.
    expect(Array.isArray(delta.entered)).toBe(true);
    expect(Array.isArray(delta.left)).toBe(true);
    expect(Array.isArray(delta.stayed)).toBe(true);

    if (delta.unavailableReason) {
      // A first version has nothing to compare with, and says so instead of reporting no changes.
      expect(delta.unavailableReason).toBe('no-previous-version');
      expect(delta.entered).toHaveLength(0);
      expect(delta.stayed).toHaveLength(0);
    } else {
      // Every compared topic carries a signed change; a topic present in one version only carries
      // none, because there is nothing to subtract from.
      for (const item of delta.stayed) {
        expect(typeof item.rankChange).toBe('number');
      }
      for (const item of [...delta.entered, ...delta.left]) {
        expect(item.rankChange ?? null).toBeNull();
      }
    }
  });

  test('слишком короткий запрос отклоняется валидацией', async () => {
    const response = await api.post('/api/v1/research-requests', { data: { query: 'ИИ' } });

    expect(response.status()).toBe(400);
    expect((await response.json()).type).toContain('validation-error');
  });
});
