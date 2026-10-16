/**
 * Browser storage for UI preferences only (F11): sidebar collapsed, theme, compact rows, the last Review tab, the
 * Live View overlay choices. Never tokens or personal data. Every access is wrapped: a blocked or full storage simply
 * means the default is used.
 */
const PREFIX = 'krz-attend.';

export function readPreference<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function writePreference(key: string, value: unknown): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Storage blocked or full: the preference lasts for this page only.
  }
}
