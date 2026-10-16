/** Events (R10, R11): crossings with filters, the crossing drawer, the filter counts, corrections and their undo. */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type EventDetail = components['schemas']['CrossingViews.EventDetail'];
export type CrossingFacets = components['schemas']['CrossingViews.Facets'];
export type CorrectionPreview = components['schemas']['CorrectionService.PreviewView'];
export type CorrectionKind = components['schemas']['CorrectionController.CorrectionRequest']['kind'];
type CrossingPage = components['schemas']['PageResponseEventRow'];
type Applied = components['schemas']['CorrectionService.Applied'];

export function useCrossings(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.crossings, 'list', query],
    queryFn: ({ signal }) => request<CrossingPage>('/crossings', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

export function useCrossingFacets(from: string, to: string) {
  return useQuery({
    queryKey: [...queryKeys.crossings, 'facets', from, to],
    queryFn: ({ signal }) => request<CrossingFacets>('/crossings/facets', { signal, query: { from, to } }),
    placeholderData: keepPreviousData,
  });
}

export function useCrossing(code: string | null) {
  return useQuery({
    queryKey: [...queryKeys.crossings, 'detail', code],
    queryFn: ({ signal }) => request<EventDetail>(`/crossings/${encodeURIComponent(code ?? '')}`, { signal }),
    enabled: code !== null,
    retry: false,
  });
}

/** The day before and after a correction (nothing is saved). */
export function useCorrectionPreview(code: string, kind: CorrectionKind) {
  return useQuery({
    queryKey: [...queryKeys.crossings, 'preview', code, kind],
    queryFn: ({ signal }) =>
      request<CorrectionPreview>(`/crossings/${encodeURIComponent(code)}/corrections/preview`, {
        method: 'POST',
        body: { kind },
        signal,
        userActivity: true,
      }),
    staleTime: 0,
    gcTime: 0,
  });
}

/** A correction changes the crossing, its day, the outbox, Today and the Dashboard. */
function useInvalidateAfterCorrection() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all(
      [queryKeys.crossings, queryKeys.attendance, queryKeys.dashboard, queryKeys.live].map(key =>
        queryClient.invalidateQueries({ queryKey: key }),
      ),
    );
}

export function useCorrection() {
  const invalidate = useInvalidateAfterCorrection();
  return useMutation({
    mutationFn: ({ code, kind, reason }: { code: string; kind: CorrectionKind; reason: string }) =>
      request<Applied>(`/crossings/${encodeURIComponent(code)}/corrections`, {
        method: 'POST',
        body: { kind, reason },
      }),
    onSettled: invalidate,
  });
}

export function useCorrectionUndo() {
  const invalidate = useInvalidateAfterCorrection();
  return useMutation({
    mutationFn: (undoToken: string) => request<undefined>('/corrections/undo', { method: 'POST', body: { undoToken } }),
    onSettled: invalidate,
  });
}
