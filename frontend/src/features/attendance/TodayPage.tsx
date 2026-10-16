/**
 * Today (the prototype's #/attendance/today): the KPI tiles and quick filters (?filter=), then every employee on
 * their current shift day, served page by page; a row opens the day drawer. Evacuation list with print and CSV.
 */
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useTodayFacets, useTodayRows, useTodayStats, type TodayRow } from '@/api/attendance';
import { apiUrl } from '@/api/client';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DataTable, type Column } from '@/components/data/DataTable';
import { KpiButton } from '@/components/data/KpiCard';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import {
  AttendanceStatusChip,
  DirectionChip,
  HrmsStatusChip,
  MethodLabel,
  TrackingChip,
} from '@/components/domain/Chips';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import type { IconName } from '@/components/icons/Icon';
import { Chip, type ChipTone } from '@/components/ui/Chip';
import { cx } from '@/lib/cx';
import { DASH, exportFileName, fmtDay, fmtDuration, fmtTime, todayDay } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { ATTENDANCE_STATUS, EMPLOYMENT_TYPES } from '@/lib/vocabulary';
import { EvacuationDialog } from './EvacuationDialog';
import '@/components/charts/Legend.module.css';

/** The prototype's TODAY_FILTERS, in its order. */
const QUICK = [
  ['all', 'All tracked'],
  ['inside', 'Inside now'],
  ['checked-in', 'Checked in'],
  ['late', 'Late'],
  ['not-in', 'Not yet in'],
  ['stepped-out', 'Stepped out'],
  ['checked-out', 'Checked out'],
  ['early-exit', 'Early exit'],
  ['absent', 'Absent'],
  ['missing', 'Missing punch'],
  ['off', 'Holiday / weekly off'],
  ['not-tracked', 'Not tracked'],
] as const;
type QuickKey = (typeof QUICK)[number][0];
const isQuick = (value: string | null): value is QuickKey => QUICK.some(([k]) => k === value);

const DEFAULT_SORT: SortState = { key: 'in', dir: 'desc' };

const COLUMNS: readonly Column<TodayRow>[] = [
  {
    key: 'emp',
    label: 'Employee',
    sort: 'name',
    lock: true,
    render: r => (
      <PersonCell
        id={r.employee.id}
        name={r.employee.name}
        sub={<EmployeeIdText employeeId={r.employee.employeeId} />}
      />
    ),
  },
  { key: 'eid', label: 'Employee ID', hidden: true, render: r => r.employee.employeeId ?? DASH },
  { key: 'dept', label: 'Department', sort: 'department', render: r => r.employee.department?.name ?? DASH },
  {
    key: 'shift',
    label: 'Shift',
    sort: 'shift',
    render: r => (
      <>
        <span className="row gap-4 nowrap">
          <span className="sw" style={{ background: r.shift.colour }} />
          {r.shift.name}
        </span>
        <div className="muted xs nowrap">{r.shift.timing}</div>
      </>
    ),
  },
  {
    key: 'status',
    label: 'Status',
    sort: 'status',
    render: r => (
      <AttendanceStatusChip status={r.status} tentative={r.status === 'CHECKED_OUT' && !r.finalised} late={r.late} />
    ),
  },
  {
    key: 'in',
    label: 'Check-in',
    sort: 'in',
    render: r =>
      r.checkIn ? (
        <>
          <span className="num nowrap">{fmtTime(r.checkIn)}</span>
          <div className="mt-4">
            <MethodLabel method={r.checkInMethod} />
          </div>
        </>
      ) : (
        <span className="muted">—</span>
      ),
  },
  {
    key: 'last',
    label: 'Last movement',
    sort: 'last',
    render: r =>
      r.lastMovement && r.lastDirection ? (
        <span className="row gap-4 nowrap">
          <DirectionChip direction={r.lastDirection} />
          <span className="num">{fmtTime(r.lastMovement)}</span>
        </span>
      ) : (
        <span className="muted">—</span>
      ),
  },
  {
    key: 'out',
    label: 'Check-out',
    sort: 'out',
    render: r => {
      if (!r.checkOut) {
        return r.missingCheckOut ? <Chip tone="purple">Missing</Chip> : <span className="muted">—</span>;
      }
      return (
        <>
          <span className="num nowrap">{fmtTime(r.checkOut)}</span>
          {!r.checkOutFinal && <div className="muted xs">tentative</div>}
        </>
      );
    },
  },
  {
    key: 'now',
    label: 'Now',
    sort: 'now',
    render: r =>
      r.now === null ? (
        <span className="muted">—</span>
      ) : r.now === 'INSIDE' ? (
        <Chip tone="green">Inside</Chip>
      ) : (
        <Chip tone="grey">Outside</Chip>
      ),
  },
  { key: 'worked', label: 'Worked', num: true, sort: 'worked', render: r => fmtDuration(r.workedMinutes) },
  {
    key: 'hrms',
    label: 'HRMS',
    sort: 'hrms',
    render: r => (r.hrmsStatus ? <HrmsStatusChip status={r.hrmsStatus} /> : <span className="muted">—</span>),
  },
  {
    key: 'tracking',
    label: 'Tracking',
    hidden: true,
    render: r => <TrackingChip tracked={r.tracked} label={r.trackingLabel} />,
  },
];

export function TodayPage() {
  const [params, setParams] = useSearchParams();
  const { openDay } = useDetailDrawers();
  const [evacuation, setEvacuation] = useState(false);
  const now = useNow(60_000);
  const quickParam = params.get('filter');
  const quick: QuickKey = isQuick(quickParam) ? quickParam : 'all';
  const stats = useTodayStats().data;
  const facets = useTodayFacets(quick).data;

  const setQuick = (key: QuickKey): void => {
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.delete('page');
        if (key === 'all') out.delete('filter');
        else out.set('filter', key);
        return out;
      },
      { replace: true },
    );
  };

  const filters = useMemo<readonly TableFilter[]>(() => {
    const count = (list: readonly { value: string; count: number }[] | undefined, value: string) =>
      list?.find(o => o.value === value)?.count ?? 0;
    return [
      { key: 'dept', label: 'Department', options: facets?.department ?? [] },
      { key: 'shift', label: 'Shift', options: facets?.shift ?? [] },
      {
        key: 'status',
        label: 'Status',
        options: Object.entries(ATTENDANCE_STATUS).map(([value, t]) => ({
          value,
          label: t.label,
          count: count(facets?.status, value),
        })),
      },
      { key: 'loc', label: 'Location', options: facets?.location ?? [] },
      {
        key: 'type',
        label: 'Employment type',
        options: Object.entries(EMPLOYMENT_TYPES).map(([value, label]) => ({
          value,
          label,
          count: count(facets?.type, value),
        })),
      },
    ];
  }, [facets]);
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    filter: quick,
    department: state.values.dept ?? [],
    shift: state.values.shift ?? [],
    status: state.values.status ?? [],
    location: state.values.loc ?? [],
    type: state.values.type ?? [],
    q: state.q,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
  };
  const result = useTodayRows({ ...filterQuery, page: state.page - 1, size: state.size });

  const kpi = (
    filter: QuickKey,
    label: string,
    value: number | undefined,
    icon: IconName,
    tone: ChipTone,
    foot: string,
  ) => (
    <KpiButton
      key={filter}
      on={quick === filter}
      onClick={() => {
        setQuick(filter);
      }}
      label={label}
      value={value ?? DASH}
      icon={icon}
      tone={tone}
      foot={foot}
    />
  );

  return (
    <>
      <PageHead
        title="Today"
        sub={`${fmtDay(todayDay(now))} · live from the entrance camera. Check-outs stay tentative until each shift’s day close.`}
        actions={
          <Button
            icon="printer"
            iconSize="default"
            onClick={() => {
              setEvacuation(true);
            }}
          >
            Evacuation list
          </Button>
        }
      />
      <div className="kpis">
        {kpi('inside', 'Inside now', stats?.inside, 'moveIn', 'green', `${String(stats?.steppedOut ?? 0)} stepped out`)}
        {kpi(
          'checked-in',
          'Checked in',
          stats?.checkedIn,
          'checkCircle',
          'green',
          `of ${String(stats?.working ?? 0)} working today`,
        )}
        {kpi('late', 'Late', stats?.late, 'clock', 'amber', 'After shift start + grace')}
        {kpi(
          'not-in',
          'Not yet in',
          stats?.notYetIn,
          'hourglass',
          'grey',
          stats?.absent ? `${String(stats.absent)} absent` : 'Absent after the cut-off',
        )}
        {kpi(
          'checked-out',
          'Checked out',
          stats?.checkedOut,
          'logout',
          'blue',
          `${String(stats?.earlyExit ?? 0)} early exit`,
        )}
        {kpi(
          'missing',
          'Missing punch',
          stats ? stats.missingCheckOut + stats.missingCheckIn : undefined,
          'alertCircle',
          'purple',
          'HRMS regularises',
        )}
        {kpi('not-tracked', 'Not tracked', stats?.notTracked, 'eyeOff', 'grey', 'HR records in HRMS')}
      </div>
      <div className="row wrap gap-4 mb-12" role="group" aria-label="Quick filters">
        {QUICK.map(([key, label]) => (
          <button
            key={key}
            type="button"
            className={cx('filter-btn', quick === key && 'on')}
            aria-pressed={quick === key}
            onClick={() => {
              setQuick(key);
            }}
          >
            {label} <span className="muted xs num">{stats?.quick[key] ?? 0}</span>
          </button>
        ))}
      </div>
      <DataTable
        id="today"
        control={control}
        filters={filters}
        columns={COLUMNS}
        rowKey={r => r.employee.id}
        result={result}
        noun="employees"
        noun1="employee"
        searchPlaceholder="Search by name or Employee ID"
        empty={{ icon: 'users', title: 'Nobody in this group', text: 'Try another quick filter.' }}
        exportCsv={{
          href: apiUrl('/attendance/today/export', filterQuery),
          fileName: exportFileName('today', now),
          permission: 'events.export',
        }}
        onRow={r => {
          openDay(r.employee.id, r.day);
        }}
      />
      {evacuation && (
        <EvacuationDialog
          onClose={() => {
            setEvacuation(false);
          }}
        />
      )}
    </>
  );
}
