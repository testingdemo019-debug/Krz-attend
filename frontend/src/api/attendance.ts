/** Attendance: Today (rows, figures, filter counts, evacuation list), the attendance log and the day drawer. */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type TodayStats = components['schemas']['AttendanceViews.TodayStats'];

/** Today's KPI figures and quick-filter counts (attendance.view.all); refreshed by attendance.changed. */
export function useTodayStats(enabled = true) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'today', 'stats'],
    queryFn: ({ signal }) => request<TodayStats>('/attendance/today/stats', { signal }),
    enabled,
    staleTime: 5_000,
  });
}

export type DayDetail = components['schemas']['AttendanceViews.DayDetail'];

/** A shift day evaluated now with the same engine (the day drawer). */
export function useDayDetail(employeeId: string, day: string) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'day', employeeId, day],
    queryFn: ({ signal }) =>
      request<DayDetail>(`/attendance/days/${encodeURIComponent(employeeId)}/${encodeURIComponent(day)}`, { signal }),
  });
}

export type TodayRow = components['schemas']['AttendanceViews.TodayRow'];
export type TodayFacets = components['schemas']['AttendanceViews.TodayFacets'];
export type Evacuation = components['schemas']['AttendanceViews.Evacuation'];
export type LogRow = components['schemas']['AttendanceViews.LogRow'];
export type LogFacets = components['schemas']['AttendanceViews.LogFacets'];
type TodayPage = components['schemas']['PageResponseTodayRow'];
type LogPage = components['schemas']['PageResponseLogRow'];

export function useTodayRows(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'today', 'rows', query],
    queryFn: ({ signal }) => request<TodayPage>('/attendance/today', { signal, query }),
    placeholderData: keepPreviousData,
    staleTime: 5_000,
  });
}

export function useTodayFacets(filter: string) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'today', 'facets', filter],
    queryFn: ({ signal }) => request<TodayFacets>('/attendance/today/facets', { signal, query: { filter } }),
    placeholderData: keepPreviousData,
  });
}

/** Who is inside now; the server audits each opening, so it is fetched when the dialog opens, never cached. */
export function useEvacuation() {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'evacuation'],
    queryFn: ({ signal }) => request<Evacuation>('/attendance/evacuation', { signal, userActivity: true }),
    staleTime: 0,
    gcTime: 0,
  });
}

export function useAttendanceLog(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'log', query],
    queryFn: ({ signal }) => request<LogPage>('/attendance/log', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

export function useLogFacets(from: string, to: string, includeNotTracked: boolean) {
  return useQuery({
    queryKey: [...queryKeys.attendance, 'log', 'facets', from, to, includeNotTracked],
    queryFn: ({ signal }) =>
      request<LogFacets>('/attendance/log/facets', { signal, query: { from, to, includeNotTracked } }),
    placeholderData: keepPreviousData,
  });
}
