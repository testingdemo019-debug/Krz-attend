/**
 * The attendance log (the prototype's #/attendance/log): one row per employee, one status code per shift day (up to
 * 31 days), the totals, the legend; a code opens the day drawer. ?day= highlights a day and shows its missing punches.
 */
import { useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { useAttendanceLog, useLogFacets, type LogRow } from '@/api/attendance';
import { apiUrl } from '@/api/client';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { Switch } from '@/components/form/Switch';
import { CardBody } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { Button, DownloadButton } from '@/components/ui/Button';
import { tip } from '@/components/ui/Tooltip';
import { cx } from '@/lib/cx';
import {
  addDays,
  daysBetween,
  DASH,
  exportFileName,
  fmtDate,
  fmtDayColumn,
  fmtDuration,
  fmtTime,
  todayDay,
  weekdayOf,
  type Day,
} from '@/lib/format';
import { useCan, useNeedsPermissionText } from '@/lib/permissions';
import { useNow } from '@/lib/useNow';
import { ATTENDANCE_STATUS, term } from '@/lib/vocabulary';
import '@/components/charts/Legend.module.css';
import '@/components/domain/CodeCell.module.css';

const DEFAULT_SORT: SortState = { key: 'name', dir: 'asc' };
const MAX_SPAN = 30;

function Cell({
  row,
  index,
  highlight,
  onOpen,
}: {
  row: LogRow;
  index: number;
  highlight: boolean;
  onOpen: () => void;
}) {
  const x = row.days[index];
  if (!x?.status) return <span className="muted xs">—</span>;
  const t = term(ATTENDANCE_STATUS, x.status);
  const label = t?.label ?? x.status;
  const detail = `${label}${x.checkIn ? ` · in ${fmtTime(x.checkIn)}` : ''}${x.checkOut ? ` · out ${fmtTime(x.checkOut)}` : ''}`;
  return (
    <button
      type="button"
      className={cx('chip', `chip-${t?.tone ?? 'grey'}`, 'code-cell', highlight && 'hl')}
      aria-label={`${row.employee.name}, ${fmtDate(x.day)}: ${label}${x.finalised ? '' : ' (not final)'}`}
      {...tip(detail)}
      onClick={onOpen}
    >
      {t?.code ?? x.statusCode}
      {x.late && x.status !== 'LATE' && <sup>L</sup>}
    </button>
  );
}

export function AttendanceLogPage() {
  const [params, setParams] = useSearchParams();
  const { openDay } = useDetailDrawers();
  const canExport = useCan('events.export');
  const needs = useNeedsPermissionText();
  const now = useNow(60_000);
  const today = todayDay(now);
  const highlight = params.get('day');
  const to = params.get('to') ?? today;
  const from = params.get('from') ?? addDays(to, -6);
  const includeNotTracked = params.get('nt') === '1';

  // The prototype's ?day=: a week around that day, its missing check-outs.
  useEffect(() => {
    if (!highlight || params.has('from')) return;
    const t = todayDay();
    const end = highlight >= addDays(t, -6) ? t : addDays(highlight, 3);
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.set('from', addDays(end, -6));
        out.set('to', end);
        out.set('has', 'MISSING_CHECK_OUT');
        return out;
      },
      { replace: true },
    );
  }, [highlight, params, setParams]);

  const setRange = (nextFrom: Day, nextTo: Day): void => {
    let a = nextFrom;
    let b = nextTo;
    if (a > b) [a, b] = [b, a];
    if (daysBetween(a, b) > MAX_SPAN) {
      a = addDays(b, -MAX_SPAN);
      toast('The log shows up to 31 days at a time', { type: 'info' });
    }
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.set('from', a);
        out.set('to', b);
        out.delete('day');
        out.delete('page');
        return out;
      },
      { replace: true },
    );
  };

  const days = useMemo(() => {
    const list: Day[] = [];
    for (let d = from; d <= to; d = addDays(d, 1)) list.push(d);
    return list;
  }, [from, to]);

  const facets = useLogFacets(from, to, includeNotTracked).data;
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      { key: 'dept', label: 'Department', options: facets?.department ?? [] },
      { key: 'shift', label: 'Shift', options: facets?.shift ?? [] },
      {
        key: 'has',
        label: 'Has status',
        options: Object.entries(ATTENDANCE_STATUS).map(([value, t]) => ({
          value,
          label: t.label,
          count: facets?.status.find(o => o.value === value)?.count ?? 0,
        })),
      },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    from,
    to,
    includeNotTracked: includeNotTracked || undefined,
    department: state.values.dept ?? [],
    shift: state.values.shift ?? [],
    status: state.values.has ?? [],
    q: state.q,
  };
  const sort = state.sort ? `${state.sort.key},${state.sort.dir}` : null;
  const result = useAttendanceLog({ ...filterQuery, sort, page: state.page - 1, size: state.size });

  const columns = useMemo<readonly Column<LogRow>[]>(
    () => [
      {
        key: 'emp',
        label: 'Employee',
        sort: 'name',
        lock: true,
        width: '220px',
        render: r => (
          <PersonCell
            id={r.employee.id}
            name={r.employee.name}
            sub={<EmployeeIdText employeeId={r.employee.employeeId} />}
          />
        ),
      },
      { key: 'eid', label: 'Employee ID', hidden: true, render: r => r.employee.employeeId ?? DASH },
      ...days.map((d, i): Column<LogRow> => ({
        key: `d${d}`,
        label: fmtDayColumn(d),
        className: cx('code-col', d === highlight && 'hl-col'),
        render: r => (
          <Cell
            row={r}
            index={i}
            highlight={d === highlight}
            onOpen={() => {
              openDay(r.employee.id, d);
            }}
          />
        ),
      })),
      { key: 'p', label: 'Present', num: true, sort: 'present', render: r => r.totals.present },
      { key: 'l', label: 'Late', num: true, sort: 'late', render: r => r.totals.late },
      { key: 'a', label: 'Absent', num: true, sort: 'absent', render: r => r.totals.absent },
      { key: 'm', label: 'Missing', num: true, sort: 'missing', render: r => r.totals.missing },
      { key: 'w', label: 'Worked', num: true, sort: 'worked', render: r => fmtDuration(r.totals.workedMinutes) },
    ],
    [days, highlight, openDay],
  );

  const preset = (key: 'week' | '7' | '14' | 'month'): void => {
    const t = todayDay();
    if (key === 'week') setRange(addDays(t, -((weekdayOf(t) + 6) % 7)), t);
    else if (key === 'month') setRange(`${t.slice(0, 8)}01`, t);
    else setRange(addDays(t, -(Number(key) - 1)), t);
  };

  return (
    <>
      <PageHead
        title="Attendance log"
        sub="One row per employee, one column per shift day. Codes come from the punch rules. Regularisation and leave are handled in KRZ HRMS."
        actions={
          canExport ? (
            <DownloadButton
              icon="download"
              iconSize="default"
              href={apiUrl('/attendance/log/export', { ...filterQuery, detail: 'daily' })}
              onClick={() => {
                toast(`Exported the daily detail to ${exportFileName('attendance', now)}`, {
                  type: 'success',
                  logged: true,
                });
              }}
            >
              Export daily detail
            </DownloadButton>
          ) : (
            <Button
              icon="download"
              iconSize="default"
              onClick={() => {
                toast(needs('events.export'), { type: 'warn' });
              }}
            >
              Export daily detail
            </Button>
          )
        }
      />
      <div className="card mb-12">
        <CardBody className="row wrap gap-12">
          <div className="field" style={{ width: 170 }}>
            <label htmlFor="log-from">From</label>
            <DateField
              id="log-from"
              value={from}
              label="From date"
              max={today}
              onChange={d => {
                if (d) setRange(d, to);
              }}
            />
          </div>
          <div className="field" style={{ width: 170 }}>
            <label htmlFor="log-to">To</label>
            <DateField
              id="log-to"
              value={to}
              label="To date"
              max={today}
              onChange={d => {
                if (d) setRange(from, d);
              }}
            />
          </div>
          <div className="row wrap gap-4" style={{ alignSelf: 'flex-end' }}>
            {(
              [
                ['This week', 'week'],
                ['Last 7 days', '7'],
                ['Last 14 days', '14'],
                ['This month', 'month'],
              ] as const
            ).map(([label, key]) => (
              <Button
                key={key}
                size="sm"
                onClick={() => {
                  preset(key);
                }}
              >
                {label}
              </Button>
            ))}
          </div>
          <span className="grow" />
          <div style={{ alignSelf: 'flex-end' }}>
            <Switch
              id="log-nt"
              label="Show not-tracked employees"
              checked={includeNotTracked}
              onChange={event => {
                const on = event.target.checked;
                setParams(
                  prev => {
                    const out = new URLSearchParams(prev);
                    if (on) out.set('nt', '1');
                    else out.delete('nt');
                    out.delete('day');
                    out.delete('page');
                    return out;
                  },
                  { replace: true },
                );
              }}
            />
          </div>
        </CardBody>
        <CardBody style={{ borderTop: '1px solid var(--border)' }}>
          <div className="legend">
            {Object.entries(ATTENDANCE_STATUS).map(([key, t]) => (
              <span key={key}>
                <span className={`chip chip-${t.tone} code-cell`} aria-hidden="true">
                  {t.code}
                </span>
                {t.label}
              </span>
            ))}
            <span>
              <sup>L</sup> late
            </span>
          </div>
        </CardBody>
      </div>
      <DataTable
        id="attlog"
        control={control}
        filters={filters}
        columns={columns}
        rowKey={r => r.employee.id}
        result={result}
        noun="employees"
        noun1="employee"
        searchPlaceholder="Search by name or Employee ID"
        empty={{ icon: 'calendar', title: 'No employees in this range' }}
        footNote={`${fmtDate(from)} – ${fmtDate(to)}`}
        exportCsv={{
          href: apiUrl('/attendance/log/export', { ...filterQuery, sort }),
          fileName: exportFileName('attendance-grid', now),
          permission: 'events.export',
        }}
      />
    </>
  );
}
