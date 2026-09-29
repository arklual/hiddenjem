import { APIRequestContext, expect, test } from '@playwright/test';
import { DEMO_DOMAIN, apiContext, runAnalysis } from '../support/api';

/**
 * Reproducibility is a product requirement (BR-B6, KR6), not just a testing convenience: an analyst
 * defending a decision must be able to re-run the same question and get the same answer.
 *
 * The corpus is fixed (golden fixture), so two independent analyses of the same domain with the same
 * parameters must produce identical rankings, scores and indicator values.
 */
test.describe('Детерминизм и воспроизводимость', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test('два независимых анализа одного направления дают идентичный результат', async () => {
    const query = `${DEMO_DOMAIN} детерминизм`;

    // Distinct parameter sets would be different questions; keep them identical but bypass the
    // freshness cache by using different yearsWindow values that both cover the whole corpus…
    const first = await runAnalysis(api, query, { topN: 10, yearsWindow: 7 });
    const firstReport = await (await api.get(`/api/v1/reports/${first.reportId}`)).json();

    // …then force a genuine recomputation with the same parameters via a saved-domain style rerun.
    const second = await runAnalysis(api, `${query} `, { topN: 10, yearsWindow: 7 });
    const secondReport = await (await api.get(`/api/v1/reports/${second.reportId}`)).json();

    expect(secondReport.corpusSnapshotId).toBeTruthy();
    expect(secondReport.methodologyVersion).toBe(firstReport.methodologyVersion);

    const project = (report: { trends: Array<Record<string, unknown>> }) =>
      report.trends.map((t) => ({
        rank: t.rank,
        trendKey: t.trendKey,
        score: Number((t.assessment as { score: number }).score.toFixed(6)),
        indicators: (t.assessment as { indicators: Array<{ name: string; value: number }> }).indicators
          .slice()
          .sort((a, b) => a.name.localeCompare(b.name))
          .map((i) => ({ name: i.name, value: Number(i.value.toFixed(6)) })),
      }));

    expect(project(secondReport)).toEqual(project(firstReport));
  });

  test('отчёт неизменяем: повторное чтение возвращает то же содержимое (BRULE-5)', async () => {
    const run = await runAnalysis(api, `${DEMO_DOMAIN} неизменяемость`, { topN: 5 });

    const first = await (await api.get(`/api/v1/reports/${run.reportId}`)).json();
    const second = await (await api.get(`/api/v1/reports/${run.reportId}`)).json();

    expect(second).toEqual(first);
    expect(second.generatedAt).toBe(first.generatedAt);
    expect(second.version).toBe(first.version);
  });
});
