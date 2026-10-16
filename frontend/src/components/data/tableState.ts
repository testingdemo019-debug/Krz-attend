/**
 * A server-side table's state in the URL query (F10): search "q", "page" (1-based), "size", "sort=key,dir", each
 * option filter under its key (repeated for several values) and each date range as "<key>From"/"<key>To" (both
 * present but empty: a default range was cleared). Other query parameters of the page (such as "tab") are left alone.
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';

export interface SortState {
  key: string;
  dir: 'asc' | 'desc';
}

export interface FilterOption {
  value: string;
  label: string;
  /** How many rows have the value (the prototype's count column); left out when unknown. */
  count?: number;
}

export interface OptionFilter {
  key: string;
  label: string;
  options: readonly FilterOption[];
  /** Applied until the user changes it (Employees: Account = Enabled); a cleared default is an empty parameter. */
  defaultValues?: readonly string[];
}

export interface RangeFilter {
  key: string;
  label: string;
  range: true;
  /** Shown until the user changes it (Events: yesterday and today); clearing it then means every day. */
  defaultRange?: DayRange;
}

export type TableFilter = OptionFilter | RangeFilter;

export interface DayRange {
  from: string;
  to: string;
}

export interface TableState {
  q: string;
  page: number;
  size: number;
  sort: SortState | null;
  values: Readonly<Record<string, readonly string[]>>;
  ranges: Readonly<Record<string, DayRange>>;
}

export interface TableControl {
  state: TableState;
  /** Changes some of the state; anything but the page also goes back to page 1. */
  set: (patch: Partial<TableState>) => void;
  /** Clears the search and every filter. */
  clear: () => void;
}

export const PAGE_SIZES = [25, 50, 100] as const;

export const isRange = (filter: TableFilter): filter is RangeFilter => 'range' in filter;

export function anyFilter(state: TableState): boolean {
  return (
    Object.values(state.values).some(v => v.length > 0) ||
    Object.values(state.ranges).some(r => r.from !== '' || r.to !== '')
  );
}

/** A date range filter's value in the URL, or its default while the URL has none. */
export function rangeOf(params: URLSearchParams, filter: RangeFilter): DayRange {
  const from = params.get(`${filter.key}From`);
  const to = params.get(`${filter.key}To`);
  return from === null && to === null
    ? (filter.defaultRange ?? { from: '', to: '' })
    : { from: from ?? '', to: to ?? '' };
}

/** @param defaultSize rows per page until the user chooses (the audit log shows 50) */
export function useTableState(
  filters: readonly TableFilter[],
  defaultSort: SortState | null,
  defaultSize: number = PAGE_SIZES[0],
): TableControl {
  const [params, setParams] = useSearchParams();

  const state = useMemo<TableState>(() => {
    const sortParam = params.get('sort')?.split(',');
    const sortKey = sortParam?.[0];
    const size = Number(params.get('size'));
    const values: Record<string, readonly string[]> = {};
    const ranges: Record<string, DayRange> = {};
    for (const f of filters) {
      if (isRange(f)) ranges[f.key] = rangeOf(params, f);
      else values[f.key] = params.has(f.key) ? params.getAll(f.key).filter(Boolean) : (f.defaultValues ?? []);
    }
    return {
      q: params.get('q') ?? '',
      page: Math.max(1, Number(params.get('page')) || 1),
      size: (PAGE_SIZES as readonly number[]).includes(size) ? size : defaultSize,
      sort: sortKey ? { key: sortKey, dir: sortParam[1] === 'desc' ? 'desc' : 'asc' } : defaultSort,
      values,
      ranges,
    };
  }, [params, filters, defaultSort, defaultSize]);

  const write = useCallback(
    (next: TableState) => {
      setParams(
        prev => {
          const out = new URLSearchParams(prev);
          const put = (key: string, value: string | null): void => {
            if (value === null || value === '') out.delete(key);
            else out.set(key, value);
          };
          put('q', next.q.trim());
          put('page', next.page > 1 ? String(next.page) : null);
          put('size', next.size !== defaultSize ? String(next.size) : null);
          const sameAsDefault = next.sort?.key === defaultSort?.key && next.sort?.dir === defaultSort?.dir;
          put('sort', next.sort && !sameAsDefault ? `${next.sort.key},${next.sort.dir}` : null);
          for (const f of filters) {
            if (isRange(f)) {
              const range = next.ranges[f.key] ?? { from: '', to: '' };
              const fallback = f.defaultRange ?? { from: '', to: '' };
              if (range.from === fallback.from && range.to === fallback.to) {
                out.delete(`${f.key}From`);
                out.delete(`${f.key}To`);
              } else {
                out.set(`${f.key}From`, range.from);
                out.set(`${f.key}To`, range.to);
              }
            } else {
              const chosen = next.values[f.key] ?? [];
              const fallback = f.defaultValues ?? [];
              out.delete(f.key);
              if (chosen.length === fallback.length && chosen.every(v => fallback.includes(v))) continue;
              if (chosen.length === 0) out.set(f.key, '');
              for (const v of chosen) out.append(f.key, v);
            }
          }
          return out;
        },
        { replace: true },
      );
    },
    [setParams, filters, defaultSort, defaultSize],
  );

  const set = useCallback(
    (patch: Partial<TableState>) => {
      write({ ...state, ...patch, page: patch.page ?? 1 });
    },
    [state, write],
  );

  const clear = useCallback(() => {
    write({ ...state, q: '', page: 1, values: {}, ranges: {} });
  }, [state, write]);

  return { state, set, clear };
}
