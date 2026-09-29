/**
 * RFC 9457 `application/problem+json` handling (ADR-0013).
 *
 * The `type` URI is `https://horizon.dev/problems/<slug>`; the slug is the stable
 * machine-readable part the frontend switches on. Slugs mirror
 * `platform-common`'s `ProblemType` enum — that enum's javadoc names the frontend
 * as its consumer and forbids renames without a version bump.
 *
 * Типов про вход, токены, роли и регистрацию здесь нет: входа в системе нет. Если такой тип всё же
 * придёт, он неизвестен сборке и разбирается по статусу — как любой незнакомый.
 */
import { z } from 'zod';
import type { Problem, ProblemFieldError } from './types';

export const PROBLEM_SLUGS = [
  'validation-error',
  'malformed-request',
  'not-found',
  'conflict',
  'illegal-state-transition',
  'quota-exceeded',
  'rate-limited',
  'upstream-unavailable',
  'analysis-failed',
  'internal-error',
] as const;

export type ProblemSlug = (typeof PROBLEM_SLUGS)[number];

const PROBLEM_BASE_URI = 'https://horizon.dev/problems/';

export const problemFieldErrorSchema = z.object({
  field: z.string(),
  message: z.string(),
  rejectedValue: z.unknown().optional(),
});

export const problemSchema = z.object({
  type: z.string(),
  title: z.string(),
  status: z.number().int(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  traceId: z.string().optional(),
  timestamp: z.string().optional(),
  retryable: z.boolean().optional(),
  errors: z.array(problemFieldErrorSchema).optional(),
});

/**
 * Extracts the stable slug from a problem `type` URI.
 *
 * Returns `null` for `about:blank` (RFC 9457's "no specific type") and for any
 * URI outside the Horizon catalogue, so callers fall back to status-based
 * handling instead of trusting an unknown slug.
 */
export function problemSlug(type: string | undefined): ProblemSlug | null {
  if (!type || type === 'about:blank') return null;
  const tail = type.startsWith(PROBLEM_BASE_URI)
    ? type.slice(PROBLEM_BASE_URI.length)
    : type.split('/').pop();
  if (!tail) return null;
  const candidate = tail.split(/[?#]/)[0];
  if (!candidate) return null;
  return (PROBLEM_SLUGS as readonly string[]).includes(candidate)
    ? (candidate as ProblemSlug)
    : null;
}

/** Parses an unknown response body into a Problem, or `null` if it is not one. */
export function parseProblem(body: unknown): Problem | null {
  const result = problemSchema.safeParse(body);
  return result.success ? result.data : null;
}

/**
 * Error thrown for every non-2xx API response.
 *
 * Always carries the HTTP status; carries a parsed `problem` when the service
 * answered with `application/problem+json` (which it does for every documented
 * error in the contract).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly slug: ProblemSlug | null;
  readonly problem: Problem | null;
  readonly retryAfterSeconds: number | null;

  constructor(
    status: number,
    problem: Problem | null,
    options: { retryAfterSeconds?: number | null; message?: string } = {},
  ) {
    super(options.message ?? problem?.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.slug = problemSlug(problem?.type);
    this.retryAfterSeconds = options.retryAfterSeconds ?? null;
  }

  get fieldErrors(): ProblemFieldError[] {
    return this.problem?.errors ?? [];
  }

  get traceId(): string | null {
    return this.problem?.traceId ?? null;
  }

  /** Whether the service itself declared the failure retryable. */
  get retryable(): boolean {
    return this.problem?.retryable ?? false;
  }
}

/** Thrown when the network call itself failed (offline, DNS, CORS, abort-free). */
export class NetworkError extends Error {
  override readonly cause: unknown;

  constructor(cause: unknown) {
    super('Не удалось связаться с сервером');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

/**
 * Thrown when a 2xx body does not match the contract schema.
 *
 * This is deliberately loud: a silent shape mismatch would let unvalidated data
 * reach the methodology breakdown, which is the one place the product cannot be
 * wrong about.
 */
export class ContractViolationError extends Error {
  readonly issues: string[];
  readonly endpoint: string;

  constructor(endpoint: string, issues: string[]) {
    super(`Ответ ${endpoint} не соответствует контракту API`);
    this.name = 'ContractViolationError';
    this.endpoint = endpoint;
    this.issues = issues;
  }
}
