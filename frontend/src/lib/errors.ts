/**
 * Maps transport failures onto something an analyst can act on.
 *
 * NFR-U3 requires every error to state a cause **and** a next action, with
 * technical detail behind a disclosure rather than inline. `toUiError` produces
 * exactly that shape; `ErrorState` renders it.
 *
 * The slug → copy table is an exhaustive `Record<ProblemSlug, …>`: if
 * `platform-common` adds a problem type and `PROBLEM_SLUGS` grows, this file
 * stops compiling until the copy exists.
 */
import { ApiError, ContractViolationError, NetworkError, type ProblemSlug } from '@/api/problem';
import type { ProblemFieldError } from '@/api/types';
import { translate, type TranslationKey } from './i18n/translate';

export type UiErrorKind = 'api' | 'network' | 'contract' | 'unknown';

export interface UiError {
  kind: UiErrorKind;
  /** Short heading. */
  title: string;
  /** What went wrong, in the user's terms. */
  message: string;
  /** What to do next. */
  action: string;
  status: number | null;
  slug: ProblemSlug | null;
  /** Server-supplied `detail`, shown only behind the disclosure. */
  detail: string | null;
  traceId: string | null;
  fieldErrors: ProblemFieldError[];
  retryable: boolean;
  retryAfterSeconds: number | null;
}

interface SlugCopy {
  /**
   * Заголовок из нашего словаря, а не с сервера.
   *
   * Сервер присылает `title` в `problem+json`, написанный по типу проблемы, — но написанный
   * по-русски: это константы перечисления `ProblemType`. Пока интерфейс предпочитал серверный
   * заголовок, в английской локали каждая ошибка выглядела так: русская шапка, английские тело и
   * действие. Словарная проверка этого не видела и не могла — строка приходит по проводу, а не
   * лежит в словаре.
   *
   * Серверный заголовок не выброшен: он остаётся запасным для типа проблемы, которого эта сборка
   * не знает. Там он точнее общего «Не удалось выполнить операцию».
   */
  title: TranslationKey;
  message: TranslationKey;
  action: TranslationKey;
}

const SLUG_COPY: Record<ProblemSlug, SlugCopy> = {
  'validation-error': {
    title: 'error.validation-errorTitle',
    message: 'error.validation-error',
    action: 'error.validation-errorAction',
  },
  'malformed-request': {
    title: 'error.malformed-requestTitle',
    message: 'error.malformed-request',
    action: 'error.malformed-requestAction',
  },
  'not-found': {
    title: 'error.not-foundTitle',
    message: 'error.not-found',
    action: 'error.not-foundAction',
  },
  conflict: {
    title: 'error.conflictTitle',
    message: 'error.conflict',
    action: 'error.conflictAction',
  },
  'illegal-state-transition': {
    title: 'error.illegal-state-transitionTitle',
    message: 'error.illegal-state-transition',
    action: 'error.illegal-state-transitionAction',
  },
  'quota-exceeded': {
    title: 'error.quota-exceededTitle',
    message: 'error.quota-exceeded',
    action: 'error.quota-exceededAction',
  },
  'rate-limited': {
    title: 'error.rate-limitedTitle',
    message: 'error.rate-limited',
    action: 'error.rate-limitedAction',
  },
  'upstream-unavailable': {
    title: 'error.upstream-unavailableTitle',
    message: 'error.upstream-unavailable',
    action: 'error.upstream-unavailableAction',
  },
  'analysis-failed': {
    title: 'error.analysis-failedTitle',
    message: 'error.analysis-failed',
    action: 'error.analysis-failedAction',
  },
  'internal-error': {
    title: 'error.internal-errorTitle',
    message: 'error.internal-error',
    action: 'error.internal-errorAction',
  },
};

/** Fallback copy keyed by HTTP status, for a problem with an unknown `type`. */
function copyForStatus(status: number): SlugCopy {
  // 401 и 403 отдельно не описаны: входа нет, API открыт, и такой ответ — ошибка развёртывания,
  // а не повод просить пользователя войти. Он получает общий текст, а не «проверьте ввод».
  if (status === 401 || status === 403) {
    return { title: 'error.title', message: 'error.unknown', action: 'error.unknownAction' };
  }
  if (status === 404) return SLUG_COPY['not-found'];
  if (status === 409) return SLUG_COPY.conflict;
  if (status === 429) return SLUG_COPY['rate-limited'];
  if (status >= 500) return SLUG_COPY['internal-error'];
  if (status >= 400) return SLUG_COPY['validation-error'];
  return { title: 'error.title', message: 'error.unknown', action: 'error.unknownAction' };
}

export function toUiError(error: unknown): UiError {
  if (error instanceof ApiError) {
    const copy = error.slug ? SLUG_COPY[error.slug] : copyForStatus(error.status);
    return {
      kind: 'api',
      // Prefer the service's own title — it is authored per problem type and is
      // already Russian — and fall back to our catalogue when it is absent.
      // Свой заголовок для известного типа проблемы, серверный — для неизвестного.
      // Порядок именно такой: серверный написан по-русски, и в английской локали он делал
      // из каждой ошибки русскую шапку с английским телом.
      title: error.slug
        ? translate(copy.title)
        : (error.problem?.title ?? translate('error.title')),
      message: translate(copy.message),
      action: translate(copy.action),
      status: error.status,
      slug: error.slug,
      detail: error.problem?.detail ?? null,
      traceId: error.traceId,
      fieldErrors: error.fieldErrors,
      retryable: error.retryable || error.status >= 500 || error.status === 429,
      retryAfterSeconds: error.retryAfterSeconds,
    };
  }

  if (error instanceof NetworkError) {
    return {
      kind: 'network',
      title: translate('error.title'),
      message: translate('error.network'),
      action: translate('error.networkAction'),
      status: null,
      slug: null,
      detail: null,
      traceId: null,
      fieldErrors: [],
      retryable: true,
      retryAfterSeconds: null,
    };
  }

  if (error instanceof ContractViolationError) {
    return {
      kind: 'contract',
      title: translate('error.title'),
      message: translate('error.contract'),
      action: translate('error.contractAction'),
      status: null,
      slug: null,
      detail: `${error.endpoint}\n${error.issues.join('\n')}`,
      traceId: null,
      fieldErrors: [],
      retryable: false,
      retryAfterSeconds: null,
    };
  }

  return {
    kind: 'unknown',
    title: translate('error.title'),
    message: translate('error.unknown'),
    action: translate('error.unknownAction'),
    status: null,
    slug: null,
    detail: error instanceof Error ? error.message : null,
    traceId: null,
    fieldErrors: [],
    retryable: true,
    retryAfterSeconds: null,
  };
}

export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.slug === 'not-found');
}

/** Field name → message, for wiring `errors[]` back onto form inputs. */
export function fieldErrorMap(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {};
  const map: Record<string, string> = {};
  for (const item of error.fieldErrors) {
    map[item.field] = item.message;
  }
  return map;
}
