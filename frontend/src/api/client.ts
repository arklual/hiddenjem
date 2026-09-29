/**
 * Composition root for the API layer.
 *
 * Без хранилища токенов и обновления сессии: входа нет, API открыт, поэтому клиент собирается
 * из одной функции и не зависит ни от какого состояния пользователя.
 */
import type { z } from 'zod';
import { createHttpClient } from './http';
import { createApi } from './endpoints';

const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

export const httpClient = createHttpClient();
export const api = createApi(httpClient);

/** Absolute URL for the SSE progress stream of a request. */
export function eventStreamUrl(requestId: string): string {
  return `${BASE_URL}${api.researchRequestEventsUrl(requestId)}`;
}

/**
 * Parses one SSE `data:` payload into a `ResearchRequestView`.
 *
 * The contract says every progress event carries a `ResearchRequestView`, so the
 * same schema that validates the polling response validates the stream — the two
 * transports can never disagree about the shape they produce.
 */
export function parseEventPayload<TParsed>(
  data: string,
  // Input typed `unknown` for the same reason as `RequestOptions.schema`: a schema with a
  // `.transform()` parses one type and yields another, and requiring them to coincide silently
  // resolves the result to the input.
  schema: z.ZodType<TParsed, z.ZodTypeDef, unknown>,
): TParsed | null {
  try {
    const parsed = schema.safeParse(JSON.parse(data) as unknown);
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}
