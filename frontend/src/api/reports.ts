/** Reports: enrolment coverage (groups, by department, the employees not covered) and recognition quality. */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type Coverage = components['schemas']['ReportViews.Coverage'];
export type CoverageFacets = components['schemas']['ReportViews.CoverageFacets'];
export type Uncovered = components['schemas']['ReportViews.Uncovered'];
export type Recognition = components['schemas']['ReportViews.Recognition'];
export type RecognitionDay = components['schemas']['ReportViews.Day'];
type UncoveredPage = components['schemas']['PageResponseUncovered'];

/** Reports are aggregates over live data: asked again whenever a report page opens. */
const FRESH = 0;

export function useCoverage() {
  return useQuery({
    queryKey: [...queryKeys.reports, 'coverage'],
    queryFn: ({ signal }) => request<Coverage>('/reports/coverage', { signal }),
    staleTime: FRESH,
  });
}

export function useUncovered(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.reports, 'coverage', 'employees', query],
    queryFn: ({ signal }) => request<UncoveredPage>('/reports/coverage/employees', { signal, query }),
    placeholderData: keepPreviousData,
    staleTime: FRESH,
  });
}

export function useCoverageFacets() {
  return useQuery({
    queryKey: [...queryKeys.reports, 'coverage', 'facets'],
    queryFn: ({ signal }) => request<CoverageFacets>('/reports/coverage/facets', { signal }),
    staleTime: FRESH,
  });
}

/** The recognition report of a period (inclusive days, Asia/Kolkata). */
export function useRecognition(from: string, to: string) {
  return useQuery({
    queryKey: [...queryKeys.reports, 'recognition', from, to],
    queryFn: ({ signal }) => request<Recognition>('/reports/recognition', { signal, query: { from, to } }),
    placeholderData: keepPreviousData,
    staleTime: FRESH,
  });
}
