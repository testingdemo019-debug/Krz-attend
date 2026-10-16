/**
 * Light, dark or the system's (the prototype's applyTheme): a per-user preference kept in this browser only (F8:
 * "theme stays a per-user preference"). Applied to html[data-theme] before the first render and on every change.
 */
import { useSyncExternalStore } from 'react';
import { readPreference, writePreference } from './storage';

export type ThemePreference = 'light' | 'dark' | 'system';

const KEY = 'theme';
const darkQuery = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null;
let preference: ThemePreference = readPreference<ThemePreference>(KEY, 'system');
const listeners = new Set<() => void>();

export function applyTheme(): void {
  const dark = preference === 'dark' || (preference === 'system' && darkQuery?.matches === true);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}

darkQuery?.addEventListener('change', applyTheme);

export function setTheme(next: ThemePreference): void {
  preference = next;
  writePreference(KEY, next);
  applyTheme();
  listeners.forEach(listener => {
    listener();
  });
}

export function useThemePreference(): ThemePreference {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => preference,
  );
}
