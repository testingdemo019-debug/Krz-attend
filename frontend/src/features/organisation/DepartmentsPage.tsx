/**
 * Departments (the prototype's #/org/departments): group employees and give a whole department one shift. The table
 * with status counts, CSV; a row (or ?id=) opens the department drawer; "New department" the two-step editor.
 */
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useDepartmentFacets, useDepartments, type DepartmentRow } from '@/api/organisation';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { HrmsBanner, SourceTag } from '@/components/domain/HrmsBanner';
import { PersonCell } from '@/components/domain/PersonCell';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { DASH, exportFileName, fmtDate } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { RECORD_STATUS, term } from '@/lib/vocabulary';
import { DepartmentDrawer } from './DepartmentDrawer';
import { DepartmentEditor } from './DepartmentEditor';

const DEFAULT_SORT: SortState = { key: 'name', dir: 'asc' };

const COLUMNS: readonly Column<DepartmentRow>[] = [
  {
    key: 'name',
    label: 'Department',
    sort: 'name',
    lock: true,
    render: d => (
      <>
        <b>{d.name}</b>
        <div className="muted xs">{d.description ?? ''}</div>
      </>
    ),
  },
  { key: 'code', label: 'Code', sort: 'code', render: d => <span className="mono">{d.code}</span> },
  {
    key: 'head',
    label: 'Head',
    sort: 'head',
    render: d =>
      d.head ? (
        <PersonCell id={d.head.id} name={d.head.displayName} sub={d.head.designation ?? ''} />
      ) : (
        <span className="muted">{DASH}</span>
      ),
  },
  { key: 'n', label: 'Employees', sort: 'employees', num: true, render: d => d.employees },
  {
    key: 'shift',
    label: 'Department shift',
    render: d => (
      <>
        <span className="row gap-4 nowrap">
          <span className="sw" style={{ background: d.shift.colour }} />
          {d.shift.name}
        </span>
        <div className="muted xs">{d.shiftSince ? `since ${fmtDate(d.shiftSince)}` : 'organisation default'}</div>
      </>
    ),
  },
  { key: 'loc', label: 'Locations', render: d => d.locations.join(', ') || DASH },
  {
    key: 'status',
    label: 'Status',
    sort: 'status',
    render: d => {
      const t = term(RECORD_STATUS, d.status);
      return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? d.status}</Chip>;
    },
  },
  { key: 'source', label: 'Source', hidden: true, render: () => <SourceTag /> },
];

export function DepartmentsPage() {
  const [params, setParams] = useSearchParams();
  const now = useNow(60_000);
  const facets = useDepartmentFacets().data;
  const [editing, setEditing] = useState<{ code: string | null } | null>(null);
  const filters = useMemo<readonly TableFilter[]>(
    () => [{ key: 'status', label: 'Status', defaultValues: ['ACTIVE'], options: facets?.status ?? [] }],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = { status: state.values.status ?? [], q: state.q };
  const result = useDepartments({
    ...filterQuery,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
    page: state.page - 1,
    size: state.size,
  });
  const open = params.get('id');
  const setOpen = (code: string | null): void => {
    setParams(
      prev => {
        const next = new URLSearchParams(prev);
        if (code) next.set('id', code);
        else next.delete('id');
        return next;
      },
      { replace: true },
    );
  };
  const newButton = (
    <Button
      variant="primary"
      icon="plus"
      onClick={() => {
        setEditing({ code: null });
      }}
    >
      New department
    </Button>
  );
  return (
    <>
      <PageHead title="Departments" sub="Group employees and give a whole department one shift." actions={newButton} />
      <HrmsBanner />
      <DataTable
        id="departments"
        control={control}
        filters={filters}
        columns={COLUMNS}
        rowKey={d => d.code}
        result={result}
        noun="departments"
        noun1="department"
        searchPlaceholder="Search departments"
        empty={{ icon: 'building', title: 'No departments yet', action: newButton }}
        exportCsv={{
          href: apiUrl('/departments/export', filterQuery),
          fileName: exportFileName('departments', now),
          permission: 'events.export',
        }}
        onRow={d => {
          setOpen(d.code);
        }}
      />
      {open && (
        <DepartmentDrawer
          key={open}
          code={open}
          onClose={() => {
            setOpen(null);
          }}
          onEdit={() => {
            setOpen(null);
            setEditing({ code: open });
          }}
        />
      )}
      {editing && (
        <DepartmentEditor
          code={editing.code}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
    </>
  );
}
