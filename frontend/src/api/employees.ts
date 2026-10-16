/** Employees: the list, filter counts, the profile, the employment editor, bulk actions and the face profile. */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type EmployeeListItem = components['schemas']['EmployeeViews.ListItem'];
export type EmployeeFacets = components['schemas']['EmployeeViews.Facets'];
export type EmployeeProfile = components['schemas']['EmployeeViews.Profile'];
export type ShiftToday = components['schemas']['EmployeeViews.ShiftToday'];
export type EmploymentRequest = components['schemas']['EmployeeController.EmploymentRequest'];
export type BulkRequest = components['schemas']['EmployeeController.BulkRequest'];
export type BulkResult = components['schemas']['EmployeeViews.BulkResult'];
export type DirectorySummary = components['schemas']['DirectoryController.Summary'];
type EmployeePage = components['schemas']['PageResponseListItem'];
type Saved = components['schemas']['EmploymentService.Saved'];
type SyncRun = components['schemas']['DirectorySyncService.SyncRunView'];

/** The prototype's employee picker shows at most 80 matches. */
const PICKER_SIZE = 80;

/** Enabled employees matching a search (name, Employee ID, department, designation), by name. */
export function useEmployeeOptions(q: string, enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.employees, 'options', q],
    queryFn: ({ signal }) =>
      request<EmployeePage>('/employees', {
        signal,
        query: { status: 'enabled', q: q.trim(), size: PICKER_SIZE, sort: 'name,asc' },
      }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

/** Every enabled employee by name, page by page (the employment editor's Manager list). */
export function useAllEnabledEmployees(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.employees, 'all-enabled'],
    queryFn: async ({ signal }) => {
      const all: EmployeeListItem[] = [];
      for (let page = 0; ; page += 1) {
        const batch = await request<EmployeePage>('/employees', {
          signal,
          query: { status: 'enabled', size: 200, page, sort: 'name,asc' },
        });
        all.push(...batch.items);
        if (all.length >= batch.total || batch.items.length === 0) return all;
      }
    },
    enabled,
  });
}

export function useEmployees(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.employees, 'list', query],
    queryFn: ({ signal }) => request<EmployeePage>('/employees', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

export function useEmployeeFacets() {
  return useQuery({
    queryKey: [...queryKeys.employees, 'facets'],
    queryFn: ({ signal }) => request<EmployeeFacets>('/employees/facets', { signal }),
  });
}

export function useDirectorySummary() {
  return useQuery({
    queryKey: [...queryKeys.employees, 'summary'],
    queryFn: ({ signal }) => request<DirectorySummary>('/directory/summary', { signal }),
  });
}

export function useEmployee(id: string) {
  return useQuery({
    queryKey: [...queryKeys.employees, 'profile', id],
    queryFn: ({ signal }) => request<EmployeeProfile>(`/employees/${encodeURIComponent(id)}`, { signal }),
    retry: false,
  });
}

/** The shift the employee would work today in another department (the employment editor). */
export function useShiftWithDepartment(id: string, department: string) {
  return useQuery({
    queryKey: [...queryKeys.employees, 'shift', id, department],
    queryFn: ({ signal }) =>
      request<ShiftToday>(`/employees/${encodeURIComponent(id)}/shift`, { signal, query: { department } }),
    enabled: department !== '',
    placeholderData: keepPreviousData,
  });
}

/** Employment, face and bulk changes reach Today, the log, the outbox and the Dashboard too. */
function useInvalidateEmployees() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all(
      [queryKeys.employees, queryKeys.attendance, queryKeys.dashboard, queryKeys.organisation].map(key =>
        queryClient.invalidateQueries({ queryKey: key }),
      ),
    );
}

export function useUpdateEmployment(id: string) {
  const invalidate = useInvalidateEmployees();
  return useMutation({
    mutationFn: (body: EmploymentRequest) =>
      request<Saved>(`/employees/${encodeURIComponent(id)}/employment`, { method: 'PUT', body }),
    onSettled: invalidate,
  });
}

export function useEmployeeBulk() {
  const invalidate = useInvalidateEmployees();
  return useMutation({
    mutationFn: (body: BulkRequest) => request<BulkResult>('/employees/bulk', { method: 'POST', body }),
    onSettled: (_data, _error, body) => (body.preview ? undefined : invalidate()),
  });
}

export type FaceAction =
  | { kind: 'deactivate'; reason: string; note: string | null }
  | { kind: 'reactivate' }
  | { kind: 'erase'; reason: string; note: string | null };

export function useFaceAction(id: string) {
  const invalidate = useInvalidateEmployees();
  return useMutation({
    mutationFn: (action: FaceAction) =>
      request<EmployeeProfile>(`/employees/${encodeURIComponent(id)}/face/${action.kind}`, {
        method: 'POST',
        body:
          action.kind === 'reactivate'
            ? {}
            : action.kind === 'erase'
              ? { confirm: 'ERASE', reason: action.reason, note: action.note }
              : { reason: action.reason, note: action.note },
      }),
    onSettled: invalidate,
  });
}

/** Directory "Sync now" (settings.integrations): the employees and Settings → Microsoft sign-in's sync history. */
export function useDirectorySync() {
  const invalidate = useInvalidateEmployees();
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => request<SyncRun>('/directory/sync', { method: 'POST' }),
    onSettled: () =>
      Promise.all([invalidate(), client.invalidateQueries({ queryKey: [...queryKeys.settings, 'signin'] })]),
  });
}
