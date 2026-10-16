/** What the shell shows on every page: the status pill, notifications, badges and the global search. */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type StatusSummary = components['schemas']['SystemStatusService.Summary'];
export type StatusRow = components['schemas']['SystemStatusService.StatusRow'];
export type StatusLevel = StatusSummary['level'];
export type InboxItem = components['schemas']['InboxService.InboxItem'];
export type SearchResults = components['schemas']['SearchService.Results'];
export type AccessRequestView = components['schemas']['AccessViews.AccessRequestView'];

/** The status pill and its popover (every signed-in user); refreshed by the system.status event. */
export function useStatusSummary() {
  return useQuery({
    queryKey: queryKeys.statusSummary,
    queryFn: ({ signal }) => request<StatusSummary>('/system/status/summary', { signal }),
    staleTime: 30_000,
  });
}

export function useNotifications() {
  return useQuery({
    queryKey: queryKeys.notifications,
    queryFn: ({ signal }) =>
      request<components['schemas']['PageResponseInboxItem']>('/notifications', { signal, query: { size: 30 } }),
    staleTime: 30_000,
  });
}

export function useUnreadCount() {
  return useQuery({
    queryKey: [...queryKeys.notifications, 'unread-count'],
    queryFn: ({ signal }) => request<Record<string, number>>('/notifications/unread-count', { signal }),
    staleTime: 30_000,
    select: data => data.unread ?? 0,
  });
}

export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) =>
      request<undefined>(`/notifications/${encodeURIComponent(code)}/read`, { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.notifications }),
  });
}

export function useMarkAllRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => request<undefined>('/notifications/read-all', { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.notifications }),
  });
}

/** Open review items per type (review.resolve): the sidebar badge, the topbar icon and the Review tabs. */
export function useReviewCounts(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.reviewCounts,
    queryFn: ({ signal }) => request<components['schemas']['ReviewViews.Counts']>('/review/counts', { signal }),
    enabled,
    staleTime: 5_000,
  });
}

export function openReviewTotal(counts: components['schemas']['ReviewViews.Counts'] | undefined): number {
  return Object.values(counts?.open ?? {}).reduce((sum, n) => sum + n, 0);
}

/** Pending access requests (settings.roles): the Settings badge. */
export function usePendingAccessRequests(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.accessRequests, 'PENDING'],
    queryFn: ({ signal }) =>
      request<AccessRequestView[]>('/settings/access-requests', { signal, query: { status: 'PENDING' } }),
    enabled,
    staleTime: 30_000,
  });
}

export function useSearch(q: string) {
  const text = q.trim();
  return useQuery({
    queryKey: queryKeys.search(text),
    queryFn: ({ signal }) => request<SearchResults>('/search', { signal, query: { q: text }, userActivity: true }),
    enabled: text.length > 0,
    staleTime: 30_000,
    placeholderData: previous => previous,
  });
}
