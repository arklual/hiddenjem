/**
 * Typed HTTP client for the Horizon API.
 *
 * Responsibilities:
 *  - turn every non-2xx response into an `ApiError` carrying the parsed
 *    RFC 9457 problem (ADR-0013);
 *  - validate every 2xx body against the contract schema before it reaches the UI.
 *
 * There is no mock, fixture or offline path here: every byte the application
 * renders comes from these calls (FR-06.2).
 *
 * Токена, заголовка Authorization и повтора после 401 здесь нет: входа в системе нет, одна
 * организация, API открыт. Любой 401 теперь — ошибка развёртывания, и показывается как ошибка.
 */
import type { z } from 'zod';
import { ApiError, ContractViolationError, NetworkError, parseProblem } from './problem';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type QueryValue = string | number | boolean | undefined | null | readonly string[];
export type QueryParams = Record<string, QueryValue>;

export interface RequestOptions<TParsed> {
  method?: HttpMethod;
  /** Serialised as JSON. Use `undefined` for no body. */
  body?: unknown;
  query?: QueryParams;
  /**
   * Contract schema for the success body. Omit only for `204 No Content`.
   *
   * The third parameter — the schema's *input* — is deliberately `unknown` rather than left to
   * default to `TParsed`. Defaulting requires input and output to coincide, which is false for every
   * schema carrying a `.transform()`: an open enum parses a `string` and yields a narrowed union.
   * With the default, inference could not satisfy both positions and silently resolved `TParsed` to
   * the input type, so callers were handed `string` where the contract promised a union — and each
   * such endpoint became a type error that nothing reported, because the project's typecheck script
   * compiled no files at all.
   */
  schema?: z.ZodType<TParsed, z.ZodTypeDef, unknown>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** `Idempotency-Key` header (UC-02 A3). */
  idempotencyKey?: string;
}

/** Base URL for the API. Empty in dev and prod alike: both proxy `/api`. */
const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

export function buildQuery(params: QueryParams | undefined): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      // Repeated key form — what Spring binds a `List<T>` query parameter from.
      for (const item of value) search.append(key, String(item));
    } else {
      search.append(key, String(value));
    }
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

function parseRetryAfter(header: string | null): number | null {
  if (!header) return null;
  const seconds = Number(header);
  if (Number.isFinite(seconds)) return seconds;
  const date = Date.parse(header);
  return Number.isNaN(date) ? null : Math.max(0, Math.round((date - Date.now()) / 1000));
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function issuesOf(error: z.ZodError): string[] {
  return error.issues.map((issue) => `${issue.path.join('.') || '<root>'}: ${issue.message}`);
}

export interface HttpClient {
  request<TParsed>(path: string, options?: RequestOptions<TParsed>): Promise<TParsed>;
  /** Raw response for non-JSON payloads (CSV export). Errors are mapped the same way. */
  requestBlob(path: string, options?: Omit<RequestOptions<never>, 'schema'>): Promise<Blob>;
}

export function createHttpClient(): HttpClient {
  async function send(path: string, options: RequestOptions<unknown>): Promise<Response> {
    const serialisedBody = options.body === undefined ? undefined : JSON.stringify(options.body);
    const headers = new Headers(options.headers);
    // Умолчание, а не приказ. Раньше эта строка безусловно затирала Accept, заданный вызывающим:
    // выгрузка просила text/csv и text/markdown, а до сервера всегда доходил JSON. Не ломалось лишь
    // потому, что ответ задаёт Content-Type сам, — то есть заголовок был объявлен и не подключён.
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json, application/problem+json');
    }
    if (serialisedBody !== undefined) headers.set('Content-Type', 'application/json');
    if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);

    const url = `${BASE_URL}${path}${buildQuery(options.query)}`;

    try {
      return await fetch(url, {
        method: options.method ?? 'GET',
        headers,
        body: serialisedBody,
        signal: options.signal ?? null,
      });
    } catch (error) {
      // An aborted request is a caller decision, not a transport failure.
      if (error instanceof DOMException && error.name === 'AbortError') throw error;
      throw new NetworkError(error);
    }
  }

  async function toApiError(response: Response): Promise<ApiError> {
    const body = await readBody(response);
    return new ApiError(response.status, parseProblem(body), {
      retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
    });
  }

  return {
    async request<TParsed>(path: string, options: RequestOptions<TParsed> = {}): Promise<TParsed> {
      const response = await send(path, options);

      if (!response.ok) throw await toApiError(response);

      if (response.status === 204 || !options.schema) {
        // Drain the body so the connection can be reused.
        await response.text();
        return undefined as TParsed;
      }

      const body = await readBody(response);
      const parsed = options.schema.safeParse(body);
      if (!parsed.success) {
        throw new ContractViolationError(path, issuesOf(parsed.error));
      }
      return parsed.data;
    },

    async requestBlob(path, options = {}): Promise<Blob> {
      const response = await send(path, options);
      if (!response.ok) throw await toApiError(response);
      return await response.blob();
    },
  };
}
