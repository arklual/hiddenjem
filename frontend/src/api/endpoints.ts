/**
 * Every operation in `contracts/openapi/horizon-api.yaml`, typed and validated.
 *
 * One function per `operationId`, named after it. Paths, query parameters and
 * response schemas are taken straight from the contract — nothing here may
 * invent a field the contract does not define.
 */
import { z } from 'zod';
import type { HttpClient } from './http';
import * as S from './schemas';
import type * as T from './types';

export const API = '/api/v1';

/** Три формата под трёх читателей: скрипт, Excel и человек, переносящий отчёт в записку. */
export type ExportFormat = 'json' | 'csv' | 'markdown';

const EXPORT_ACCEPT: Record<ExportFormat, string> = {
  json: 'application/json',
  csv: 'text/csv',
  markdown: 'text/markdown',
};

export interface PageQuery {
  page?: number;
  size?: number;
}

/** Пауза между опросами, которую прерывает тот же сигнал, что и сами запросы. */
function pause(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Polling aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, ms);
    const abort = (): void => {
      clearTimeout(timer);
      reject(new DOMException('Polling aborted', 'AbortError'));
    };
    signal?.addEventListener('abort', abort, { once: true });
  });
}

export function createApi(http: HttpClient) {
  return {
    /* ── Research ────────────────────────────────────────────── */

    submitResearchRequest(
      body: T.SubmitResearchRequest,
      idempotencyKey?: string,
      signal?: AbortSignal,
    ): Promise<T.ResearchRequestView> {
      return http.request(`${API}/research-requests`, {
        method: 'POST',
        body,
        schema: S.researchRequestViewSchema,
        ...(idempotencyKey ? { idempotencyKey } : {}),
        signal,
      });
    },

    listResearchRequests(
      query: PageQuery & { status?: T.ResearchStatus; onlyMine?: boolean },
      signal?: AbortSignal,
    ): Promise<T.ResearchRequestPage> {
      return http.request(`${API}/research-requests`, {
        query: {
          page: query.page,
          size: query.size,
          status: query.status,
          // Отправляется всегда: серверное умолчание — вся организация, поэтому «только мои»
          // и «вся организация» должны быть выразимы обе, а не одна через отсутствие параметра.
          ...(query.onlyMine === undefined ? {} : { onlyMine: query.onlyMine }),
        },
        schema: S.researchRequestPageSchema,
        signal,
      });
    },

    getResearchRequest(requestId: string, signal?: AbortSignal): Promise<T.ResearchRequestView> {
      return http.request(`${API}/research-requests/${encodeURIComponent(requestId)}`, {
        schema: S.researchRequestViewSchema,
        signal,
      });
    },

    cancelResearchRequest(requestId: string): Promise<T.ResearchRequestView> {
      return http.request(`${API}/research-requests/${encodeURIComponent(requestId)}/cancel`, {
        method: 'POST',
        schema: S.researchRequestViewSchema,
      });
    },

    /** URL of the SSE progress stream; consumed by `streamSse`, not by `http`. */
    researchRequestEventsUrl(requestId: string): string {
      return `${API}/research-requests/${encodeURIComponent(requestId)}/events`;
    },

    /* ── Reports ─────────────────────────────────────────────── */

    getReport(reportId: string, signal?: AbortSignal): Promise<T.TrendReport> {
      return http.request(`${API}/reports/${encodeURIComponent(reportId)}`, {
        schema: S.trendReportSchema,
        signal,
      });
    },

    /**
     * What changed since the previous version of the same report.
     *
     * Computed on read from two already-immutable reports — O(n) over at most 50 topics, with no
     * call to the analysis engine — so it is safe on a render path, unlike `explainReportTerms`.
     */
    /** Which features this build has switched on. Read once; drives what the UI offers at all. */
    listFeatures(signal?: AbortSignal): Promise<T.FeatureFlag[]> {
      return http.request(`${API}/features`, {
        schema: z.array(S.featureFlagSchema),
        signal,
      });
    },

    /** Queue every tracked direction. Returns a row per direction, including the refusals. */
    refreshRadar(signal?: AbortSignal): Promise<T.RadarRefresh[]> {
      return http.request(`${API}/saved-domains/refresh`, {
        method: 'POST',
        schema: z.array(S.radarRefreshSchema),
        signal,
      });
    },

    /**
     * Отметить направление просмотренным.
     *
     * Отдельное действие, а не побочный эффект загрузки радара: пометить всё просмотренным в момент
     * открытия радара значило бы стереть ответ на вопрос, ради которого его открыли.
     */
    markDirectionSeen(savedDomainId: string): Promise<void> {
      return http.request(`${API}/saved-domains/${encodeURIComponent(savedDomainId)}/seen`, {
        method: 'POST',
      });
    },

    /**
     * Писали ли мы про эту тему раньше и где именно.
     *
     * GET, а не POST: запрос ничего не меняет, а ссылку на выдачу пересылают коллеге — и открыться
     * она обязана в его границах видимости, а не показать сохранённый чужой ответ.
     */
    /**
     * Направления, которые система умеет соотнести с корпусом.
     *
     * Подсказка для пустого поля ввода. Пустой ответ — допустимый: подсказка помогает, но не
     * является условием работы экрана, и недоступность движка не должна мешать вводить направление
     * руками.
     */
    knownDirections(signal?: AbortSignal): Promise<string[]> {
      return http.request(`${API}/directions/known`, {
        schema: S.knownDirectionsSchema,
        signal,
      });
    },

    /**
     * Понимает ли движок эту формулировку — до запуска анализа.
     *
     * Спрашивается по ходу набора, поэтому запрос обязан быть отменяемым: набравший быстрее ответа
     * получил бы вердикт по предыдущей букве.
     */
    resolveDirection(query: string, signal?: AbortSignal): Promise<T.DirectionResolution> {
      return http.request(`${API}/directions/resolve?query=${encodeURIComponent(query)}`, {
        schema: S.directionResolutionSchema,
        signal,
      });
    },

    searchTopics(q: string, signal?: AbortSignal): Promise<T.TopicSearchResult> {
      return http.request(`${API}/topics/search?q=${encodeURIComponent(q)}`, {
        schema: S.topicSearchResultSchema,
        signal,
      });
    },

    /**
     * Topics that surfaced in more than one tracked direction.
     *
     * Its own call rather than part of the digest: the radar opens on every visit, and this answers
     * a question the analyst asks deliberately.
     */
    getDirectionOverlap(signal?: AbortSignal): Promise<T.DirectionOverlap> {
      return http.request(`${API}/saved-domains/overlap`, {
        schema: S.directionOverlapSchema,
        signal,
      });
    },

    /** Остаток часового бюджета — оценка, а не обязательство. */
    getQuota(signal?: AbortSignal): Promise<T.Quota> {
      return http.request(`${API}/research-requests/quota`, {
        schema: S.quotaSchema,
        signal,
      });
    },

    /** The state of every tracked direction, in one request. */
    getRadarDigest(signal?: AbortSignal): Promise<T.RadarDigest[]> {
      return http.request(`${API}/saved-domains/digest`, {
        schema: z.array(S.radarDigestSchema),
        signal,
      });
    },

    getReportDelta(reportId: string, signal?: AbortSignal): Promise<T.ReportDelta> {
      return http.request(`${API}/reports/${encodeURIComponent(reportId)}/delta`, {
        schema: S.reportDeltaSchema,
        signal,
      });
    },

    /**
     * "Why is this technology not in my report?"
     *
     * Slower than every other read by design — the engine replays the analysis with these terms
     * watched — so callers must not put it on a render path.
     */
    explainReportTerms(
      reportId: string,
      terms: readonly string[],
      signal?: AbortSignal,
    ): Promise<T.TermExplanation> {
      const query = terms.map((term) => `term=${encodeURIComponent(term)}`).join('&');
      // Сервер не держит соединение, пока движок считает: минутный запрос обрывают прокси между
      // аналитиком и стендом. Пока прогон идёт, ответ — 202, и тот же запрос повторяется.
      const poll = async (): Promise<T.TermExplanation> => {
        const answer = await http.request(
          `${API}/reports/${encodeURIComponent(reportId)}/explain?${query}`,
          {
            schema: z.union([S.termExplanationPendingSchema, S.termExplanationSchema]),
            signal,
          },
        );
        if (!('state' in answer)) return answer;
        await pause(answer.retryAfterSeconds * 1000, signal);
        return poll();
      };
      return poll();
    },

    getReportTrend(
      reportId: string,
      trendKey: string,
      signal?: AbortSignal,
    ): Promise<T.RankedTrend> {
      return http.request(
        `${API}/reports/${encodeURIComponent(reportId)}/trends/${encodeURIComponent(trendKey)}`,
        { schema: S.rankedTrendSchema, signal },
      );
    },

    exportReport(reportId: string, format: ExportFormat): Promise<Blob> {
      return http.requestBlob(`${API}/reports/${encodeURIComponent(reportId)}/export`, {
        query: { format },
        headers: { Accept: EXPORT_ACCEPT[format] },
      });
    },

    /* ── Feedback ────────────────────────────────────────────── */

    submitTrendFeedback(
      reportId: string,
      trendKey: string,
      body: T.TrendFeedbackRequest,
    ): Promise<T.TrendFeedbackView> {
      return http.request(
        `${API}/reports/${encodeURIComponent(reportId)}/trends/${encodeURIComponent(trendKey)}/feedback`,
        { method: 'PUT', body, schema: S.trendFeedbackViewSchema },
      );
    },

    /**
     * Снять свою пометку с темы.
     *
     * <p>Отдельная операция, а не вердикт «полезно»: аналитик, скрывший тему по ошибке, имел в виду
     * «я ошибся». Заставлять его объявить тему полезной ради отмены собственного действия значит
     * записать утверждение, которого он не делал, — и следующий отчёт его унаследует.
     */
    withdrawTrendFeedback(reportId: string, trendKey: string): Promise<void> {
      return http.request(
        `${API}/reports/${encodeURIComponent(reportId)}/trends/${encodeURIComponent(trendKey)}/feedback`,
        { method: 'DELETE' },
      );
    },

    /* ── Saved domains ───────────────────────────────────────── */

    listSavedDomains(signal?: AbortSignal): Promise<T.SavedDomain[]> {
      return http.request(`${API}/saved-domains`, { schema: S.savedDomainListSchema, signal });
    },

    saveDomain(body: T.SaveDomainRequest): Promise<T.SavedDomain> {
      return http.request(`${API}/saved-domains`, {
        method: 'POST',
        body,
        schema: S.savedDomainSchema,
      });
    },

    deleteSavedDomain(id: string): Promise<void> {
      return http.request(`${API}/saved-domains/${encodeURIComponent(id)}`, { method: 'DELETE' });
    },

    /* ── Sources ─────────────────────────────────────────────── */

    listSources(signal?: AbortSignal): Promise<T.SourceView[]> {
      return http.request(`${API}/sources`, { schema: S.sourceViewListSchema, signal });
    },

    getOpenAlexQuota(signal?: AbortSignal): Promise<T.OpenAlexQuota> {
      return http.request(`${API}/sources/openalex/quota`, {
        schema: S.openAlexQuotaSchema,
        signal,
      });
    },

    updateSource(sourceId: string, body: T.UpdateSourceRequest): Promise<T.SourceView> {
      return http.request(`${API}/sources/${encodeURIComponent(sourceId)}`, {
        method: 'PATCH',
        body,
        schema: S.sourceViewSchema,
      });
    },

    triggerIngestionRun(
      sourceId: string,
      body?: T.TriggerIngestionRequest,
    ): Promise<T.IngestionRunView> {
      return http.request(`${API}/sources/${encodeURIComponent(sourceId)}/runs`, {
        method: 'POST',
        body: body ?? {},
        schema: S.ingestionRunViewSchema,
      });
    },

    listIngestionRuns(
      query: PageQuery & { sourceId?: string },
      signal?: AbortSignal,
    ): Promise<T.IngestionRunPage> {
      return http.request(`${API}/ingestion-runs`, {
        query: { page: query.page, size: query.size, sourceId: query.sourceId },
        schema: S.ingestionRunPageSchema,
        signal,
      });
    },

    /* ── Словарь направлений ─────────────────────────────────── */

    /** Открытый список: входа нет, словарь пополняет любой, кто работает с системой. */
    listUnrecognizedDirections(
      limit?: number,
      signal?: AbortSignal,
    ): Promise<T.UnrecognizedDirection[]> {
      return http.request(`${API}/admin/unrecognized-directions`, {
        query: { limit },
        schema: S.unrecognizedDirectionsSchema,
        signal,
      });
    },
  };
}

export type Api = ReturnType<typeof createApi>;
