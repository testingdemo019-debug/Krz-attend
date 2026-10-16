/** The Review Queue (R12): open items per type, the resolved history, decisions, bulk decisions and undo. */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReviewType } from '@/lib/vocabulary';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type ReviewItem = components['schemas']['ReviewViews.Item'];
export type ReviewSighting = components['schemas']['ReviewViews.Sighting'];
export type ReviewCandidate = components['schemas']['ReviewViews.Candidate'];
export type ReviewApplied = components['schemas']['ReviewViews.Applied'];
export type ReviewFacets = components['schemas']['ReviewViews.Facets'];
export type DecisionRequest = components['schemas']['ReviewController.DecisionRequest'];
export type BulkRequest = components['schemas']['ReviewController.BulkRequest'];
type ReviewPage = components['schemas']['PageResponseItem'];

/** The most open items one tab lists (the API's page size limit); they come oldest first. */
const OPEN_PAGE = 200;

export interface OpenFilters {
  type: ReviewType;
  direction: string;
  band: string;
  from: string;
  to: string;
  waiting: boolean;
}

export function useOpenReviewItems(f: OpenFilters) {
  return useQuery({
    queryKey: [...queryKeys.review, 'open', f],
    queryFn: ({ signal }) =>
      request<ReviewPage>('/review', {
        signal,
        query: {
          type: f.type,
          direction: f.direction,
          // Unknown faces have no candidates, so no confidence band.
          band: f.type === 'UNKNOWN' ? '' : f.band,
          from: f.from,
          to: f.to,
          waiting: f.waiting || undefined,
          size: OPEN_PAGE,
        },
      }),
    placeholderData: keepPreviousData,
  });
}

/** The Resolved tab: items no longer open, with the table's filters, sort and page. */
export function useClosedReviewItems(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.review, 'closed', query],
    queryFn: ({ signal }) => request<ReviewPage>('/review', { signal, query: { ...query, status: 'closed' } }),
    placeholderData: keepPreviousData,
  });
}

export function useReviewFacets(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.review, 'facets'],
    queryFn: ({ signal }) => request<ReviewFacets>('/review/facets', { signal }),
    enabled,
  });
}

/** One item (the ?item= link: which tab it belongs to). */
export function useReviewItem(code: string | null) {
  return useQuery({
    queryKey: [...queryKeys.review, 'item', code],
    queryFn: ({ signal }) => request<ReviewItem>(`/review/${encodeURIComponent(code ?? '')}`, { signal }),
    enabled: code !== null,
    retry: false,
  });
}

/** A decision changes crossings, attendance days, the outbox and the dashboard as well as the queue. */
function useInvalidateAfterDecision() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all(
      [queryKeys.review, queryKeys.crossings, queryKeys.attendance, queryKeys.dashboard, queryKeys.live].map(key =>
        queryClient.invalidateQueries({ queryKey: key }),
      ),
    );
}

export function useReviewDecision() {
  const invalidate = useInvalidateAfterDecision();
  return useMutation({
    mutationFn: ({ code, body }: { code: string; body: DecisionRequest }) =>
      request<ReviewApplied>(`/review/${encodeURIComponent(code)}/decision`, { method: 'POST', body }),
    onSettled: invalidate,
  });
}

export function useReviewBulk() {
  const invalidate = useInvalidateAfterDecision();
  return useMutation({
    mutationFn: (body: BulkRequest) => request<ReviewApplied>('/review/bulk', { method: 'POST', body }),
    onSettled: invalidate,
  });
}

export function useReviewUndo() {
  const invalidate = useInvalidateAfterDecision();
  return useMutation({
    mutationFn: (undoToken: string) => request<undefined>('/review/undo', { method: 'POST', body: { undoToken } }),
    onSettled: invalidate,
  });
}
