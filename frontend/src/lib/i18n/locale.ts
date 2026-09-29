/**
 * Locale plumbing shared by the dictionaries and the formatters.
 *
 * Kept in its own module (rather than inside the React context) because
 * `format.ts` must read the active locale without importing React — number and
 * date formatting is used in plain functions and in tests.
 */

export const LOCALES = ['ru', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = 'ru';

/** BCP-47 tags handed to `Intl`. */
export const INTL_LOCALE: Record<Locale, string> = {
  ru: 'ru-RU',
  en: 'en-GB',
};

let activeLocale: Locale = DEFAULT_LOCALE;

const listeners = new Set<(locale: Locale) => void>();

export function getLocale(): Locale {
  return activeLocale;
}

export function getIntlLocale(): string {
  return INTL_LOCALE[activeLocale];
}

export function setLocale(locale: Locale): void {
  if (locale === activeLocale) return;
  activeLocale = locale;
  if (typeof document !== 'undefined') document.documentElement.lang = locale;
  for (const listener of [...listeners]) listener(locale);
}

export function subscribeLocale(listener: (locale: Locale) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isLocale(value: string): value is Locale {
  return (LOCALES as readonly string[]).includes(value);
}
