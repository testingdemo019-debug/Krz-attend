/**
 * Settings → Audit log (read-only): every sign-in, change, export and correction, with who did it and their roles at
 * the time. The shared table: the last 7 days by default, category and person filters with their counts over the
 * dates, 50 rows a page, newest first; IP address and entry code in the column chooser; CSV with export permission.
 */
import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useAudit, useAuditFacets, type AuditEntry } from '@/api/settings';
import { DataTable, type Column } from '@/components/data/DataTable';
import {
  rangeOf,
  useTableState,
  type RangeFilter,
  type SortState,
  type TableFilter,
} from '@/components/data/tableState';
import { addDays, exportFileName, fmtDate, fmtTime, todayDay } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { AUDIT_CATEGORIES, term } from '@/lib/vocabulary';

const DEFAULT_SORT: SortState = { key: 'ts', dir: 'desc' };
const PAGE_SIZE = 50;

const COLUMNS: readonly Column<AuditEntry>[] = [
  {
    key: 'ts',
    label: 'Time',
    sort: 'ts',
    render: a => (
      <>
        <span className="nowrap num">{fmtTime(a.occurredAt, true)}</span>
        <div className="muted xs nowrap">{fmtDate(a.occurredAt)}</div>
      </>
    ),
  },
  {
    key: 'actor',
    label: 'Who',
    sort: 'actor',
    render: a => (
      <>
        {a.actor}
        <div className="muted xs">{a.roles ?? ''}</div>
      </>
    ),
  },
  {
    key: 'cat',
    label: 'Category',
    sort: 'category',
    render: a => <span className="tag">{term(AUDIT_CATEGORIES, a.category) ?? a.category}</span>,
  },
  {
    key: 'action',
    label: 'Action',
    sort: 'action',
    render: a => (
      <>
        <b>{a.action}</b>
        <div className="small muted-2">{a.target ?? ''}</div>
      </>
    ),
  },
  {
    key: 'change',
    label: 'Change',
    render: a =>
      a.before || a.after ? (
        <span className="small">
          {a.before && (
            <>
              <span className="muted">{a.before}</span> →{' '}
            </>
          )}
          {a.after ?? ''}
        </span>
      ) : (
        <span className="muted">—</span>
      ),
  },
  { key: 'ip', label: 'IP address', hidden: true, render: a => <span className="mono xs">{a.ip ?? ''}</span> },
  { key: 'id', label: 'Entry', hidden: true, render: a => <span className="mono xs">{a.code}</span> },
];

export function AuditLogSettings() {
  const now = useNow(60_000);
  const [params] = useSearchParams();
  const today = todayDay(now);
  const dateFilter = useMemo<RangeFilter>(
    () => ({ key: 'date', label: 'Date', range: true, defaultRange: { from: addDays(today, -6), to: today } }),
    [today],
  );
  const range = rangeOf(params, dateFilter);
  const q = params.get('q') ?? '';
  const facets = useAuditFacets({ from: range.from, to: range.to, q }).data;
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      dateFilter,
      { key: 'cat', label: 'Category', options: facets?.category ?? [] },
      { key: 'actor', label: 'Person', options: facets?.actor ?? [] },
    ],
    [dateFilter, facets],
  );
  const control = useTableState(filters, DEFAULT_SORT, PAGE_SIZE);
  const { state } = control;
  const dates = state.ranges.date ?? { from: '', to: '' };
  const filterQuery = {
    category: state.values.cat ?? [],
    actor: state.values.actor ?? [],
    from: dates.from,
    to: dates.to,
    q: state.q,
  };
  const sort = state.sort ? `${state.sort.key},${state.sort.dir}` : null;
  const result = useAudit({ ...filterQuery, sort, page: state.page - 1, size: state.size });
  return (
    <div className="card pad">
      <h2>Audit log</h2>
      <p className="muted small mt-4 mb-12">
        Read-only. Every sign-in, change, export and correction, with who did it and their roles at the time.
      </p>
      <DataTable
        id="audit"
        control={control}
        filters={filters}
        columns={COLUMNS}
        rowKey={a => a.code}
        result={result}
        noun="entries"
        noun1="entry"
        searchPlaceholder="Search by person, action or target"
        empty={{ icon: 'history', title: 'No entries' }}
        exportCsv={{
          href: apiUrl('/audit/export', { ...filterQuery, sort }),
          fileName: exportFileName('audit-log', now),
          permission: 'events.export',
        }}
      />
    </div>
  );
}
