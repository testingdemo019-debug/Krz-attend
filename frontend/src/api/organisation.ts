/**
 * Organisation (settings.attendance): locations, departments, shifts, shift allocations and holidays — lists with
 * filter counts, details and every change. Changes refresh the organisation and what follows from it (employees'
 * shifts today, attendance, the Dashboard).
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

type S = components['schemas'];
export type DepartmentRow = S['OrganisationViews.DepartmentRow'];
export type DepartmentDetail = S['OrganisationViews.DepartmentDetail'];
export type LocationView = S['OrganisationViews.LocationView'];
export type ShiftCard = S['OrganisationViews.ShiftCard'];
export type ShiftDetail = S['OrganisationViews.ShiftDetail'];
export type RulesView = S['OrganisationViews.RulesView'];
export type RecalculationImpact = S['OrganisationViews.RecalculationImpact'];
export type AllocationRow = S['OrganisationViews.AllocationRow'];
export type PlanView = S['OrganisationViews.PlanView'];
export type CommitResult = S['OrganisationViews.CommitResult'];
export type HistoryEntry = S['OrganisationViews.HistoryEntry'];
export type LookupRow = S['OrganisationViews.LookupRow'];
export type HolidayView = S['OrganisationViews.HolidayView'];
export type ImportPreview = S['OrganisationViews.ImportPreview'];
export type ImportResult = S['OrganisationViews.ImportResult'];
export type StatusFacets = S['OrganisationViews.StatusFacets'];
export type AllocationFacets = S['OrganisationViews.AllocationFacets'];
export type HolidayFacets = S['OrganisationViews.HolidayFacets'];
export type MoveResult = S['OrganisationViews.MoveResult'];
export type CreateDepartmentRequest = S['DepartmentController.CreateDepartmentRequest'];
export type UpdateDepartmentRequest = S['DepartmentController.UpdateDepartmentRequest'];
export type CreateLocationRequest = S['LocationController.CreateLocationRequest'];
export type UpdateLocationRequest = S['LocationController.UpdateLocationRequest'];
export type RulesRequest = S['ShiftController.RulesRequest'];
export type CreateShiftRequest = S['ShiftController.CreateShiftRequest'];
export type NewVersionRequest = S['ShiftController.NewVersionRequest'];
export type AllocationRequest = S['AllocationController.AllocationRequest'];
export type HolidayRequest = S['HolidayController.HolidayRequest'];
interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** The largest page the API serves: every department or location of the organisation fits in one. */
const ALL = 200;
const key = (...parts: unknown[]) => [...queryKeys.organisation, ...parts];
const path = (base: string, code: string, rest = '') => `${base}/${encodeURIComponent(code)}${rest}`;

/** Every department, by name (pickers and editors). */
export function useAllDepartments(enabled = true) {
  return useQuery({
    queryKey: key('departments', 'all'),
    queryFn: ({ signal }) =>
      request<Page<DepartmentRow>>('/departments', { signal, query: { size: ALL, sort: 'name,asc' } }),
    enabled,
    select: page => page.items,
  });
}

/** Every location, by name (pickers and editors). */
export function useAllLocations(enabled = true) {
  return useQuery({
    queryKey: key('locations', 'all'),
    queryFn: ({ signal }) =>
      request<Page<LocationView>>('/locations', { signal, query: { size: ALL, sort: 'name,asc' } }),
    enabled,
    select: page => page.items,
  });
}

/** Active shifts to choose from, oldest first (the prototype's order in the allocation wizard and editors). */
export function shiftChoices(cards: readonly ShiftCard[]): ShiftCard[] {
  return cards.filter(s => s.status === 'ACTIVE').sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/** Every shift card: active first, then by name (the Shifts page, pickers and the allocation wizard). */
export function useShifts(enabled = true) {
  return useQuery({
    queryKey: key('shifts'),
    queryFn: ({ signal }) => request<Page<ShiftCard>>('/shifts', { signal, query: { size: ALL } }),
    enabled,
    select: page => page.items,
  });
}

function useList<T>(base: string, query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: key(base, 'list', query),
    queryFn: ({ signal }) => request<Page<T>>(`/${base}`, { signal, query }),
    placeholderData: keepPreviousData,
  });
}

export const useDepartments = (query: Record<string, QueryValue>) => useList<DepartmentRow>('departments', query);
export const useLocations = (query: Record<string, QueryValue>) => useList<LocationView>('locations', query);
export const useAllocations = (query: Record<string, QueryValue>) => useList<AllocationRow>('allocations', query);
export const useHolidays = (query: Record<string, QueryValue>) => useList<HolidayView>('holidays', query);

export function useDepartmentFacets() {
  return useQuery({
    queryKey: key('departments', 'facets'),
    queryFn: ({ signal }) => request<StatusFacets>('/departments/facets', { signal }),
  });
}

export function useLocationFacets() {
  return useQuery({
    queryKey: key('locations', 'facets'),
    queryFn: ({ signal }) => request<StatusFacets>('/locations/facets', { signal }),
  });
}

export function useAllocationFacets() {
  return useQuery({
    queryKey: key('allocations', 'facets'),
    queryFn: ({ signal }) => request<AllocationFacets>('/allocations/facets', { signal }),
  });
}

export function useHolidayFacets(year: number) {
  return useQuery({
    queryKey: key('holidays', 'facets', year),
    queryFn: ({ signal }) => request<HolidayFacets>('/holidays/facets', { signal, query: { year } }),
  });
}

/** Every holiday of a year, by date (the year view). */
export function useHolidayYear(year: number) {
  return useQuery({
    queryKey: key('holidays', 'year', year),
    queryFn: ({ signal }) =>
      request<Page<HolidayView>>('/holidays', { signal, query: { year, size: ALL, sort: 'date,asc' } }),
    select: page => page.items,
  });
}

export function useDepartment(code: string | null) {
  return useQuery({
    queryKey: key('departments', 'detail', code),
    queryFn: ({ signal }) => request<DepartmentDetail>(path('/departments', code ?? ''), { signal }),
    enabled: code !== null,
  });
}

export function useLocationDetail(code: string | null) {
  return useQuery({
    queryKey: key('locations', 'detail', code),
    queryFn: ({ signal }) => request<LocationView>(path('/locations', code ?? ''), { signal }),
    enabled: code !== null,
  });
}

export function useShift(code: string | null) {
  return useQuery({
    queryKey: key('shifts', 'detail', code),
    queryFn: ({ signal }) => request<ShiftDetail>(path('/shifts', code ?? ''), { signal }),
    enabled: code !== null,
  });
}

/** Every allocation of one target with its changes (the allocation's History). */
export function useAllocationHistory(target: { type: 'DEPARTMENT' | 'EMPLOYEE'; id: string } | null) {
  return useQuery({
    queryKey: key('allocations', 'history', target),
    queryFn: ({ signal }) =>
      request<HistoryEntry[]>('/allocations/history', {
        signal,
        query: { targetType: target?.type ?? '', targetId: target?.id ?? '' },
      }),
    enabled: target !== null,
  });
}

/** "Check someone's shift": the person's shift on a day and why. */
export function useShiftLookup(employeeName: string | null, day: string) {
  return useQuery({
    queryKey: key('allocations', 'lookup', employeeName, day),
    queryFn: ({ signal }) =>
      request<Page<LookupRow>>('/allocations/lookup', { signal, query: { q: employeeName ?? '', day, size: 20 } }),
    enabled: employeeName !== null,
    select: page => page.items,
  });
}

/** What an allocation would change (the wizard's Impact step), asked again whenever the draft changes. */
export function useAllocationPlan(draft: AllocationRequest | null) {
  return useQuery({
    queryKey: key('allocations', 'plan', draft),
    queryFn: ({ signal }) =>
      request<PlanView>('/allocations/plan', { method: 'POST', signal, body: draft ?? undefined }),
    enabled: draft !== null,
    placeholderData: keepPreviousData,
  });
}

/** What saving a shift version from a day recalculates. */
export function useRecalculationImpact(code: string | null, effectiveFrom: string) {
  return useQuery({
    queryKey: key('shifts', 'impact', code, effectiveFrom),
    queryFn: ({ signal }) =>
      request<RecalculationImpact>(path('/shifts', code ?? '', '/versions/preview'), {
        method: 'POST',
        signal,
        body: { effectiveFrom: effectiveFrom || null },
      }),
    enabled: code !== null && effectiveFrom !== '',
  });
}

/** Organisation changes reach employees' shifts today, attendance, the Dashboard and the employee lists. */
function useOrganisationMutation<V, R>(fn: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () =>
      Promise.all(
        [queryKeys.organisation, queryKeys.employees, queryKeys.attendance, queryKeys.dashboard].map(k =>
          queryClient.invalidateQueries({ queryKey: k }),
        ),
      ),
  });
}

export const useCreateLocation = () =>
  useOrganisationMutation((body: CreateLocationRequest) =>
    request<LocationView>('/locations', { method: 'POST', body }),
  );
export const useUpdateLocation = (code: string) =>
  useOrganisationMutation((body: UpdateLocationRequest) =>
    request<LocationView>(path('/locations', code), { method: 'PUT', body }),
  );
export const useLocationStatus = () =>
  useOrganisationMutation(({ code, archive }: { code: string; archive: boolean }) =>
    request<LocationView>(path('/locations', code, archive ? '/archive' : '/restore'), { method: 'POST' }),
  );
export const useAddEntryPoint = (code: string) =>
  useOrganisationMutation((name: string) =>
    request<LocationView>(path('/locations', code, '/entry-points'), { method: 'POST', body: { name } }),
  );
export const useRenameEntryPoint = (code: string) =>
  useOrganisationMutation(({ entryPoint, name, version }: { entryPoint: string; name: string; version: number }) =>
    request<LocationView>(path('/locations', code, `/entry-points/${encodeURIComponent(entryPoint)}`), {
      method: 'PUT',
      body: { name, version },
    }),
  );
export const useArchiveEntryPoint = (code: string) =>
  useOrganisationMutation((entryPoint: string) =>
    request<LocationView>(path('/locations', code, `/entry-points/${encodeURIComponent(entryPoint)}/archive`), {
      method: 'POST',
    }),
  );

export const useCreateDepartment = () =>
  useOrganisationMutation((body: CreateDepartmentRequest) =>
    request<DepartmentDetail>('/departments', { method: 'POST', body }),
  );
export const useUpdateDepartment = (code: string) =>
  useOrganisationMutation((body: UpdateDepartmentRequest) =>
    request<DepartmentDetail>(path('/departments', code), { method: 'PUT', body }),
  );
export const useArchiveDepartment = () =>
  useOrganisationMutation(({ code, moveTo }: { code: string; moveTo: string | null }) =>
    request<DepartmentDetail>(path('/departments', code, '/archive'), { method: 'POST', body: { moveTo } }),
  );
export const useRestoreDepartment = () =>
  useOrganisationMutation((code: string) =>
    request<DepartmentDetail>(path('/departments', code, '/restore'), { method: 'POST' }),
  );
export const useDeleteDepartment = () =>
  useOrganisationMutation((code: string) => request<undefined>(path('/departments', code), { method: 'DELETE' }));

export const useCreateShift = () =>
  useOrganisationMutation((body: CreateShiftRequest) => request<ShiftDetail>('/shifts', { method: 'POST', body }));
export const useUpdateShift = (code: string) =>
  useOrganisationMutation((body: { name: string; colour: string | null; version: number }) =>
    request<ShiftDetail>(path('/shifts', code), { method: 'PUT', body }),
  );
export const useAddShiftVersion = (code: string) =>
  useOrganisationMutation((body: NewVersionRequest) =>
    request<ShiftDetail>(path('/shifts', code, '/versions'), { method: 'POST', body }),
  );
export const useShiftAction = () =>
  useOrganisationMutation(({ code, action }: { code: string; action: 'archive' | 'restore' | 'make-default' }) =>
    request<ShiftDetail>(path('/shifts', code, `/${action}`), { method: 'POST' }),
  );

export const useCommitAllocation = () =>
  useOrganisationMutation((body: AllocationRequest) => request<CommitResult>('/allocations', { method: 'POST', body }));
export const useEndAllocation = () =>
  useOrganisationMutation(({ code, until }: { code: string; until: string | null }) =>
    until === null
      ? request<AllocationRow>(path('/allocations', code, '/cancel'), { method: 'POST' })
      : request<AllocationRow>(path('/allocations', code, '/end'), { method: 'POST', body: { until } }),
  );

export const useSaveHoliday = () =>
  useOrganisationMutation(({ code, body }: { code: string | null; body: HolidayRequest & { version?: number } }) =>
    code === null
      ? request<HolidayView>('/holidays', { method: 'POST', body })
      : request<HolidayView>(path('/holidays', code), { method: 'PUT', body }),
  );
export const useDeleteHoliday = () =>
  useOrganisationMutation((code: string) => request<undefined>(path('/holidays', code), { method: 'DELETE' }));
export const usePreviewHolidayImport = () =>
  useMutation({
    mutationFn: (csv: string) => request<ImportPreview>('/holidays/import/preview', { method: 'POST', body: { csv } }),
  });
export const useImportHolidays = () =>
  useOrganisationMutation((csv: string) =>
    request<ImportResult>('/holidays/import', { method: 'POST', body: { csv } }),
  );
