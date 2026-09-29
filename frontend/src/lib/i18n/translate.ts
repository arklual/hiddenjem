/**
 * Dictionary lookup with compile-time-checked keys.
 *
 * `TranslationKey` is derived from the Russian reference dictionary, so a typo
 * in `t('lifecycle.EMERGING')` is a build error rather than a stray key rendered
 * to an analyst. Lookup falls back ru ← en ← the key itself, which means a
 * partially translated locale degrades to Russian rather than to blanks.
 */
import { en } from './en';
import { ru, type Dictionary } from './ru';
import { getLocale, type Locale } from './locale';

type Join<K, P> = K extends string ? (P extends string ? `${K}.${P}` : never) : never;

type Paths<T> = T extends string
  ? never
  : {
      [K in keyof T & string]: T[K] extends string ? K : Join<K, Paths<T[K]>>;
    }[keyof T & string];

export type TranslationKey = Paths<Dictionary>;

export type TranslationParams = Record<string, string | number>;

function lookup(source: unknown, path: readonly string[]): string | undefined {
  let current: unknown = source;
  for (const segment of path) {
    if (typeof current !== 'object' || current === null) return undefined;
    current = (current as Record<string, unknown>)[segment];
  }
  return typeof current === 'string' ? current : undefined;
}

/** Replaces `{name}` placeholders. */
function interpolate(template: string, params: TranslationParams | undefined): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}

const DICTIONARIES: Record<Locale, unknown> = { ru, en };

/**
 * Translates a key in the currently active locale.
 *
 * Usable outside React (error mapping, formatters) — it reads the module-level
 * active locale rather than a context.
 */
export function translate(
  key: TranslationKey,
  params?: TranslationParams,
  locale: Locale = getLocale(),
): string {
  const path = key.split('.');
  const localized = locale === 'ru' ? undefined : lookup(DICTIONARIES[locale], path);
  // Falling back to the whole key would render "sourceClass.THESIS" in the UI when the backend
  // introduces a member this build does not know. The last segment is the raw value, which is at
  // least truthful and readable.
  const fallback = path[path.length - 1] ?? key;
  return interpolate(localized ?? lookup(ru, path) ?? fallback, params);
}

export { translate as t };
