/**
 * Shift allocation (the prototype's #/org/allocations): who works which shift, from when. An individual allocation
 * beats a department allocation, which beats the organisation default. The table (Active and Scheduled at first),
 * end or cancel, each allocation's history, "Check someone's shift" and the allocation wizard.
 */
import { useMemo, useState } from 'react';
import { apiUrl } from '@/api/client';
import type { EmployeeListItem } from '@/api/employees';
import {
  useAllocationFacets,
  useAllocationHistory,
  useAllocations,
  useEndAllocation,
  useShiftLookup,
  useShifts,
  type AllocationRow,
} from '@/api/organisation';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { HrmsBanner } from '@/components/domain/HrmsBanner';
import { PersonCell } from '@/components/domain/PersonCell';
import { ShiftPreview } from '@/components/domain/ShiftPreview';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { EmployeePicker } from '@/components/form/EmployeePicker';
import { Icon } from '@/components/icons/Icon';
import { PageHead } from '@/components/layout/PageHead';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { exportFileName, fmtDate, fmtDateTime, fmtDay, todayDay } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { ALLOCATION_STATE, term } from '@/lib/vocabulary';
import '@/components/domain/Timeline.module.css';
import '@/components/form/Field.module.css';

const DEFAULT_SORT: SortState = { key: 'from', dir: 'desc' };

function StateChip({ state }: { state: string }) {
  const t = term(ALLOCATION_STATE, state);
  return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? state}</Chip>;
}

export function AllocationsPage() {
  const now = useNow(60_000);
  const facets = useAllocationFacets().data;
  const defaultShift = useShifts().data?.find(s => s.defaultShift);
  const { openAllocation } = useDetailDrawers();
  const [ending, setEnding] = useState<AllocationRow | null>(null);
  const [history, setHistory] = useState<AllocationRow | null>(null);
  const [lookup, setLookup] = useState(false);
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      { key: 'state', label: 'State', defaultValues: ['ACTIVE', 'SCHEDULED'], options: facets?.state ?? [] },
      { key: 'type', label: 'Applies to', options: facets?.type ?? [] },
      { key: 'shift', label: 'Shift', options: facets?.shift ?? [] },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    state: state.values.state ?? [],
    targetType: state.values.type ?? [],
    shift: state.values.shift ?? [],
    q: state.q,
  };
  const result = useAllocations({
    ...filterQuery,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
    page: state.page - 1,
    size: state.size,
  });
  const columns = useMemo<readonly Column<AllocationRow>[]>(
    () => [
      {
        key: 'target',
        label: 'Applies to',
        sort: 'target',
        lock: true,
        render: a =>
          a.targetType === 'DEPARTMENT' ? (
            <>
              <span className="row gap-4">
                <Icon name="building" size="sm" />
                <b>{a.targetName}</b>
              </span>
              <div className="muted xs">{a.targetDetail ?? ''}</div>
            </>
          ) : (
            <PersonCell id={a.targetId} name={a.targetName} sub={a.targetDetail ?? ''} />
          ),
      },
      {
        key: 'shift',
        label: 'Shift',
        sort: 'shift',
        render: a => (
          <>
            <span className="row gap-4 nowrap">
              <span className="sw" style={{ background: a.shift.colour }} />
              {a.shift.name}
            </span>
            <div className="muted xs nowrap">{a.timing}</div>
          </>
        ),
      },
      { key: 'from', label: 'From', sort: 'from', render: a => <span className="nowrap">{fmtDate(a.from)}</span> },
      {
        key: 'until',
        label: 'Until',
        sort: 'until',
        render: a =>
          a.until ? <span className="nowrap">{fmtDate(a.until)}</span> : <span className="muted">Open-ended</span>,
      },
      { key: 'state', label: 'State', sort: 'state', render: a => <StateChip state={a.state} /> },
      {
        key: 'by',
        label: 'Created',
        sort: 'created',
        render: a => (
          <>
            {a.createdBy ?? 'System'}
            <div className="muted xs">{fmtDate(a.createdAt)}</div>
          </>
        ),
      },
      { key: 'note', label: 'Note', render: a => <span className="small">{a.note ?? ''}</span> },
      {
        key: 'act',
        label: '',
        lock: true,
        render: a => (
          <span className="row gap-4 nowrap">
            {(a.state === 'ACTIVE' || a.state === 'SCHEDULED') && (
              <Button
                size="sm"
                onClick={() => {
                  setEnding(a);
                }}
              >
                {a.state === 'SCHEDULED' ? 'Cancel' : 'End allocation'}
              </Button>
            )}
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setHistory(a);
              }}
            >
              History
            </Button>
          </span>
        ),
      },
    ],
    [],
  );
  const allocate = (
    <Button
      variant="primary"
      icon="plus"
      onClick={() => {
        openAllocation({});
      }}
    >
      Allocate shift
    </Button>
  );
  return (
    <>
      <PageHead
        title="Shift allocation"
        sub={`Who works which shift, from when. An individual allocation beats a department allocation, which beats the organisation default (${defaultShift?.name ?? '—'}).`}
        actions={
          <>
            <Button
              icon="search"
              onClick={() => {
                setLookup(true);
              }}
            >
              Check someone’s shift
            </Button>
            {allocate}
          </>
        }
      />
      <HrmsBanner />
      <DataTable
        id="allocations"
        control={control}
        filters={filters}
        columns={columns}
        rowKey={a => a.code}
        result={result}
        noun="allocations"
        noun1="allocation"
        searchPlaceholder="Search allocations"
        empty={{
          icon: 'route',
          title: 'No allocations',
          text: 'Everyone works the organisation default shift.',
          action: allocate,
        }}
        exportCsv={{
          href: apiUrl('/allocations/export', filterQuery),
          fileName: exportFileName('shift-allocations', now),
          permission: 'events.export',
        }}
      />
      {ending && (
        <EndDialog
          allocation={ending}
          onClose={() => {
            setEnding(null);
          }}
        />
      )}
      {history && (
        <HistoryDialog
          allocation={history}
          onClose={() => {
            setHistory(null);
          }}
        />
      )}
      {lookup && (
        <LookupDialog
          onClose={() => {
            setLookup(false);
          }}
        />
      )}
    </>
  );
}

/** The prototype's alloc-end: a scheduled allocation is cancelled; an active one ends after a chosen day. */
function EndDialog({ allocation: a, onClose }: { allocation: AllocationRow; onClose: () => void }) {
  const today = todayDay();
  const scheduled = a.state === 'SCHEDULED';
  const end = useEndAllocation();
  const first = a.from > today ? a.from : today;
  const [until, setUntil] = useState(first);
  return (
    <Modal
      open
      size="sm"
      title={scheduled ? 'Cancel this allocation?' : 'End this allocation?'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            pending={end.isPending}
            onClick={() => {
              end.mutate(
                { code: a.code, until: scheduled ? null : until },
                {
                  onSuccess: () => {
                    onClose();
                    toast(scheduled ? 'Allocation cancelled' : `Allocation ends after ${fmtDate(until)}`, {
                      type: 'success',
                      logged: true,
                    });
                  },
                  onError: error => toast(error.message, { type: 'error' }),
                },
              );
            }}
          >
            {scheduled ? 'Cancel allocation' : 'End allocation'}
          </Button>
        </>
      }
    >
      <p>
        {scheduled
          ? `${a.targetName} won’t move to ${a.shift.name} on ${fmtDate(a.from)}.`
          : `${a.targetName} stays on ${a.shift.name} until the date you choose, then falls back to ${
              a.targetType === 'EMPLOYEE' ? 'their department’s shift' : 'the organisation default'
            }.`}
      </p>
      {!scheduled && (
        <div className="field mt-12">
          <label htmlFor="ae-until">Last day on this shift</label>
          <DateField id="ae-until" value={until} label="Last day" min={first} onChange={setUntil} />
        </div>
      )}
    </Modal>
  );
}

/** The prototype's alloc-history: the allocation's facts and its recorded changes. */
function HistoryDialog({ allocation: a, onClose }: { allocation: AllocationRow; onClose: () => void }) {
  const entries = useAllocationHistory({ type: a.targetType as 'DEPARTMENT' | 'EMPLOYEE', id: a.targetId }).data;
  const changes = entries?.find(e => e.allocation.code === a.code)?.changes ?? [];
  return (
    <Modal open title={`History · ${a.code}`} sub={`${a.targetName} · ${a.shift.name}`} onClose={onClose}>
      <KeyValueList
        pairs={[
          ['Created', `${fmtDateTime(a.createdAt)} by ${a.createdBy ?? 'System'}`],
          ['From', fmtDate(a.from)],
          ['Until', a.until ? fmtDate(a.until) : 'Open-ended'],
          ['State', term(ALLOCATION_STATE, a.state)?.label ?? a.state],
          ['Note', a.note ?? '—'],
        ]}
      />
      <h3 className="mt-16 mb-8">Changes</h3>
      {changes.length > 0 ? (
        <div className="timeline">
          {changes.map((c, i) => (
            <div key={`${c.at}-${String(i)}`} className="tl-item">
              <div className="small">
                <b>{c.action}</b> · {c.actor}
              </div>
              <div className="muted xs">
                {fmtDateTime(c.at)} · {c.after ?? ''}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <p className="muted small">No changes recorded since it was created.</p>
      )}
    </Modal>
  );
}

/** The prototype's alloc-lookup: an employee's shift on a day, why, and what kind of day it is. */
function LookupDialog({ onClose }: { onClose: () => void }) {
  const [picked, setPicked] = useState<EmployeeListItem | null>(null);
  const [picking, setPicking] = useState(false);
  const [day, setDay] = useState(todayDay());
  const shifts = useShifts().data ?? [];
  const row = useShiftLookup(picked?.name ?? null, day).data?.find(r => r.employeeId === picked?.id);
  const card = shifts.find(s => s.code === row?.shift.code);
  const rules = card?.scheduled && card.scheduled.effectiveFrom === row?.versionFrom ? card.scheduled : card?.today;
  return (
    <>
      <Modal open size="lg" title="Check someone’s shift" onClose={onClose}>
        <div className="row wrap gap-12">
          <Button
            icon={picked ? undefined : 'user'}
            onClick={() => {
              setPicking(true);
            }}
          >
            {picked ? (
              <>
                <Avatar id={picked.id} name={picked.name} size="sm" />
                {picked.name}
              </>
            ) : (
              'Choose employee'
            )}
          </Button>
          <span style={{ width: 190 }}>
            <DateField value={day} label="Date" onChange={setDay} />
          </span>
        </div>
        {!picked ? (
          <p className="muted small">Choose an employee and a date.</p>
        ) : row ? (
          <div className="card pad mt-12">
            <div className="row gap-8 mb-8">
              <Avatar id={picked.id} name={picked.name} size="sm" />
              <b>{picked.name}</b>
              <span className="muted small">{fmtDay(day)}</span>
            </div>
            <KeyValueList
              pairs={[
                [
                  'Shift',
                  <span className="row gap-4">
                    <span className="sw" style={{ background: row.shift.colour }} />
                    {row.shift.name} · {row.timing}
                  </span>,
                ],
                ['Why', row.sourceText],
                ['Rules version', row.versionFrom ? `from ${fmtDate(row.versionFrom)}` : '—'],
                [
                  'That day',
                  row.dayType === 'HOLIDAY'
                    ? `Holiday — ${row.holiday ?? ''}`
                    : row.dayType === 'WEEKLY_OFF'
                      ? 'Weekly off'
                      : 'Working day',
                ],
              ]}
            />
            {rules && <ShiftPreview shift={rules} colour={row.shift.colour} />}
          </div>
        ) : null}
      </Modal>
      <EmployeePicker
        open={picking}
        onClose={() => {
          setPicking(false);
        }}
        onPick={e => {
          setPicked(e);
          setPicking(false);
        }}
      />
    </>
  );
}
