/**
 * toast() from anywhere (mutations, the API client): the prototype's toast(msg, {type, logged, action, timeout}).
 * Timeouts as in the prototype: 4.2 s, or 10 s with an action; undo toasts pass the undo window instead (F6.10).
 */
import { useSyncExternalStore, type ReactNode } from 'react';

export type ToastType = 'success' | 'error' | 'warn' | 'info';

export interface ToastOptions {
  type?: ToastType;
  /** Shows the "Logged" marker: the action was written to the audit log. */
  logged?: boolean;
  action?: { label: string; run: () => void };
  /** Milliseconds; undo toasts stay for the server's undo window. */
  timeout?: number;
}

export interface ToastItem {
  id: number;
  message: ReactNode;
  type: ToastType;
  logged: boolean;
  action: ToastOptions['action'] | null;
  leaving: boolean;
}

const LEAVE_MS = 160;
let items: readonly ToastItem[] = [];
let nextId = 1;
const listeners = new Set<() => void>();

function emit(next: readonly ToastItem[]): void {
  items = next;
  listeners.forEach(listener => {
    listener();
  });
}

function dismiss(id: number): void {
  if (!items.some(t => t.id === id && !t.leaving)) return;
  emit(items.map(t => (t.id === id ? { ...t, leaving: true } : t)));
  setTimeout(() => {
    emit(items.filter(t => t.id !== id));
  }, LEAVE_MS);
}

/** Shows a toast and returns a function that closes it. */
export function toast(message: ReactNode, options: ToastOptions = {}): () => void {
  const id = nextId++;
  emit([
    ...items,
    {
      id,
      message,
      type: options.type ?? 'info',
      logged: options.logged ?? false,
      action: options.action ?? null,
      leaving: false,
    },
  ]);
  const close = (): void => {
    dismiss(id);
  };
  setTimeout(close, options.timeout ?? (options.action ? 10_000 : 4_200));
  return close;
}

export function useToasts(): readonly ToastItem[] {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => items,
  );
}

export function dismissToast(id: number): void {
  dismiss(id);
}
