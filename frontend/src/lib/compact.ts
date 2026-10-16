/**
 * The "Compact rows" preference (the prototype's state.ui.compact): body.compact tightens the rows of every table.
 * Kept per browser in storage.ts; every table's toolbar toggles the same value.
 */
import { useSyncExternalStore } from 'react';
import { readPreference, writePreference } from './storage';

const KEY = 'compact-rows';
let compact = readPreference(KEY, false);
const listeners = new Set<() => void>();

/** Before the first render (main.tsx), and after every change. */
export function applyCompactRows(): void {
  document.body.classList.toggle('compact', compact);
}

export function toggleCompactRows(): void {
  compact = !compact;
  writePreference(KEY, compact);
  applyCompactRows();
  listeners.forEach(listener => {
    listener();
  });
}

export function useCompactRows(): boolean {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => compact,
  );
}
