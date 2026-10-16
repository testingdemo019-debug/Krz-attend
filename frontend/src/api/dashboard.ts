import { useQuery } from '@tanstack/react-query';
import { request } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type Dashboard = components['schemas']['DashboardViews.Dashboard'];
export type DashboardAlert = components['schemas']['DashboardViews.Alert'];

/** GET /dashboard: each section null without its permission; refreshed by realtime events. */
export function useDashboard() {
  return useQuery({
    queryKey: queryKeys.dashboard,
    queryFn: ({ signal }) => request<Dashboard>('/dashboard', { signal }),
    staleTime: 5_000,
  });
}
