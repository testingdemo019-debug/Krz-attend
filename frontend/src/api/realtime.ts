/**
 * One EventSource per tab to /api/v1/realtime/stream (F4). Each server event invalidates the queries it affects; the
 * stream reconnects with back-off when the server closes it (restart, session end). SSE never carries
 * X-User-Activity, so it does not keep an idle session alive.
 */
import type { QueryClient, QueryKey } from '@tanstack/react-query';
import { API_BASE } from './client';
import { queryKeys } from './queryKeys';

const BACKOFF_SECONDS = [1, 2, 5, 10, 30] as const;

/** Which queries each event refreshes. */
const INVALIDATES: Record<string, readonly QueryKey[]> = {
  'review.counts': [queryKeys.review, queryKeys.dashboard],
  'crossing.created': [queryKeys.crossings, queryKeys.live, queryKeys.dashboard],
  'attendance.changed': [queryKeys.attendance, queryKeys.dashboard],
  'notification.created': [queryKeys.notifications],
  'camera.status': [queryKeys.live, queryKeys.cameras, queryKeys.statusSummary, queryKeys.dashboard],
  'system.status': [queryKeys.statusSummary, queryKeys.system],
  'access.requests': [queryKeys.accessRequests, queryKeys.me],
};

export function connectRealtime(queryClient: QueryClient): () => void {
  let source: EventSource | null = null;
  let attempt = 0;
  let retry: ReturnType<typeof setTimeout> | null = null;
  let closed = false;

  const open = (): void => {
    source = new EventSource(`${API_BASE}/realtime/stream`, { withCredentials: true });
    source.onopen = () => {
      attempt = 0;
    };
    for (const [event, keys] of Object.entries(INVALIDATES)) {
      source.addEventListener(event, () => {
        for (const queryKey of keys) void queryClient.invalidateQueries({ queryKey });
      });
    }
    source.onerror = () => {
      // The browser retries by itself while the stream is CONNECTING; once CLOSED it is up to us.
      if (source?.readyState !== EventSource.CLOSED || closed) return;
      source.close();
      const delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)] ?? 30;
      attempt += 1;
      retry = setTimeout(open, delay * 1000);
    };
  };

  open();
  return () => {
    closed = true;
    if (retry) clearTimeout(retry);
    source?.close();
  };
}
