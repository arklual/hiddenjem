/**
 * React binding for the translator.
 *
 * `useSyncExternalStore` subscribes to the module-level locale so every
 * component re-renders on a language switch without a context provider having to
 * wrap the tree twice (the formatters read the same source).
 */
import { useCallback, useSyncExternalStore } from 'react';
import { getLocale, setLocale, subscribeLocale, type Locale } from './locale';
import { translate, type TranslationKey, type TranslationParams } from './translate';

function subscribe(onChange: () => void): () => void {
  return subscribeLocale(onChange);
}

export function useLocale(): {
  locale: Locale;
  setLocale: (locale: Locale) => void;
} {
  const locale = useSyncExternalStore(subscribe, getLocale, getLocale);
  return { locale, setLocale };
}

export type TranslateFn = (key: TranslationKey, params?: TranslationParams) => string;

export function useT(): TranslateFn {
  const locale = useSyncExternalStore(subscribe, getLocale, getLocale);
  return useCallback(
    (key: TranslationKey, params?: TranslationParams) => translate(key, params, locale),
    [locale],
  );
}
