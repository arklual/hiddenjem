/**
 * Theme preference.
 *
 * `system` is the default and follows `prefers-color-scheme`; an explicit choice
 * stamps `data-theme` on the root element, which the token stylesheet lets win
 * over the media query in both directions.
 *
 * The preference is a UI setting, not data — persisting it in `localStorage` is
 * unrelated to the no-mock-data rule, and no credential ever goes there.
 */
import { useCallback, useEffect, useState } from 'react';

export type ThemePreference = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'horizon.theme';

function isThemePreference(value: string | null): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system';
}

export function readStoredTheme(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return isThemePreference(stored) ? stored : 'system';
  } catch {
    // Private mode / disabled storage — fall back to following the OS.
    return 'system';
  }
}

export function applyTheme(preference: ThemePreference): void {
  const root = document.documentElement;
  if (preference === 'system') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', preference);
  }
}

export function useTheme(): {
  theme: ThemePreference;
  setTheme: (preference: ThemePreference) => void;
} {
  const [theme, setThemeState] = useState<ThemePreference>(readStoredTheme);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const setTheme = useCallback((preference: ThemePreference) => {
    setThemeState(preference);
    try {
      localStorage.setItem(STORAGE_KEY, preference);
    } catch {
      // Preference simply will not survive a reload; not worth surfacing.
    }
  }, []);

  return { theme, setTheme };
}
