/**
 * Shifts (the prototype's #/org/shifts): a card per shift with today's rules, its timeline, scheduled changes and
 * where it is allocated; the drawer (Details, or ?id=) with the rules, versions and allocations; deactivate,
 * reactivate, make default; the wizard to create, duplicate or change a shift from a date.
 */
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useShift, useShiftAction, useShifts, type ShiftCard } from '@/api/organisation';
import { HrmsBanner } from '@/components/domain/HrmsBanner';
import { ShiftPreview } from '@/components/domain/ShiftPreview';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { Drawer } from '@/components/feedback/Drawer';
import { EmptyState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { PageHead } from '@/components/layout/PageHead';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { Skeleton } from '@/components/ui/Skeleton';
import { fmtDate } from '@/lib/format';
import { plural } from '@/lib/text';
import { ALLOCATION_STATE, RECORD_STATUS, term } from '@/lib/vocabulary';
import { ShiftWizard, type ShiftWizardStart } from './ShiftWizard';
import '@/components/data/DataTable.module.css';
import '@/components/layout/Card.module.css';

function StatusChip({ status }: { status: string }) {
  const t = term(RECORD_STATUS, status);
  return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? status}</Chip>;
}

export function ShiftsPage() {
  const [params, setParams] = useSearchParams();
  const shifts = useShifts().data;
  const [wizard, setWizard] = useState<ShiftWizardStart | null>(null);
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
  return (
    <>
      <PageHead
        title="Shifts"
        sub="Working hours and the rules that decide late, early exit, half day and absent. Changes take effect from a date you choose — past days keep the rules they had."
        actions={
          <Button
            variant="primary"
            icon="plus"
            onClick={() => {
              setWizard({ source: null, duplicate: false });
            }}
          >
            New shift
          </Button>
        }
      />
      <HrmsBanner />
      <div className="grid grid-2">
        {shifts === undefined ? (
          <div className="card pad">
            <Skeleton className="sk-line" width="50%" />
          </div>
        ) : shifts.length === 0 ? (
          <div className="card">
            <EmptyState icon="timer" title="No shifts yet" />
          </div>
        ) : (
          shifts.map(s => (
            <ShiftCardView
              key={s.code}
              shift={s}
              onOpen={() => {
                setOpen(s.code);
              }}
              onEdit={() => {
                setWizard({ source: s, duplicate: false });
              }}
              onDuplicate={() => {
                setWizard({ source: s, duplicate: true });
              }}
            />
          ))
        )}
      </div>
      {open && (
        <ShiftDrawer
          key={open}
          code={open}
          onClose={() => {
            setOpen(null);
          }}
          onWizard={start => {
            setOpen(null);
            setWizard(start);
          }}
        />
      )}
      {wizard && (
        <ShiftWizard
          start={wizard}
          onClose={() => {
            setWizard(null);
          }}
        />
      )}
    </>
  );
}

function ShiftCardView({
  shift: s,
  onOpen,
  onEdit,
  onDuplicate,
}: {
  shift: ShiftCard;
  onOpen: () => void;
  onEdit: () => void;
  onDuplicate: () => void;
}) {
  const sv = s.today;
  const active = s.status === 'ACTIVE';
  return (
    <div className={`card ${active ? '' : 'muted'}`}>
      <div className="card-head">
        <h2 className="row gap-8">
          <span className="sw" style={{ background: s.colour, width: 14, height: 14 }} />
          {s.name} <span className="mono muted small">{s.code}</span>
        </h2>
        <div className="row gap-4">
          {s.defaultShift && <Chip tone="blue">Default</Chip>}
          <StatusChip status={s.status} />
        </div>
      </div>
      <div className="card-body">
        <div className="row wrap gap-16 small mb-8">
          <span>
            <b>{sv.timing}</b>
          </span>
          <span className="muted">Break {sv.breakMinutes} min</span>
          <span className="muted">Weekly off: {sv.weeklyOffText}</span>
          <span className="muted">{plural(s.employeesToday, 'employee')} today</span>
          <span className="muted">
            Late after {sv.lateGraceMinutes} min · early exit {sv.earlyGraceMinutes} min
          </span>
          <span className="muted">
            Allocated to {plural(s.departmentAllocations, 'department')} · {plural(s.employeeAllocations, 'employee')}
            {s.defaultShift ? ' · everyone else' : ''}
          </span>
        </div>
        <ShiftPreview shift={sv} colour={s.colour} />
        {s.scheduled && (
          <Callout tone="neutral" icon="calendar" className="mt-8 small">
            Changes scheduled from <b>{fmtDate(s.scheduled.effectiveFrom)}</b>: {s.scheduled.timing}
          </Callout>
        )}
        <div className="row wrap gap-8 mt-12">
          <Button size="sm" onClick={onOpen}>
            Details
          </Button>
          <Button size="sm" icon="edit" disabled={!active} onClick={onEdit}>
            Edit
          </Button>
          <Button size="sm" icon="copy" onClick={onDuplicate}>
            Duplicate
          </Button>
        </div>
      </div>
    </div>
  );
}

function ShiftDrawer({
  code,
  onClose,
  onWizard,
}: {
  code: string;
  onClose: () => void;
  onWizard: (start: ShiftWizardStart) => void;
}) {
  const detail = useShift(code).data;
  const action = useShiftAction();
  const confirm = useConfirm();
  const defaultName = useShifts().data?.find(x => x.defaultShift)?.name ?? '';
  const s = detail?.shift;
  const run = (kind: 'archive' | 'restore' | 'make-default', done: string): void => {
    action.mutate(
      { code, action: kind },
      {
        onSuccess: () => {
          onClose();
          toast(done, { type: 'success', logged: true });
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };
  const footer = detail && s && (
    <>
      {s.status === 'ACTIVE' ? (
        <Button
          icon="ban"
          disabled={detail.archiveBlockedReason !== null}
          tooltip={detail.archiveBlockedReason ?? undefined}
          onClick={() => {
            void confirm({
              title: `Deactivate ${s.name}?`,
              message: 'It can’t be allocated any more. Past attendance keeps it.',
              confirmText: 'Deactivate',
            }).then(ok => {
              if (ok) run('archive', `${s.name} deactivated`);
            });
          }}
        >
          Deactivate
        </Button>
      ) : (
        <Button
          icon="refresh"
          onClick={() => {
            run('restore', `${s.name} reactivated`);
          }}
        >
          Reactivate
        </Button>
      )}
      {s.status === 'ACTIVE' && !s.defaultShift && (
        <Button
          onClick={() => {
            void confirm({
              title: `Make ${s.name} the default shift?`,
              message: `Employees with no department or individual allocation move from ${defaultName} to ${s.name} from today.`,
              confirmText: 'Make default',
            }).then(ok => {
              if (ok) run('make-default', `${s.name} is now the default shift`);
            });
          }}
        >
          Make default
        </Button>
      )}
      <span className="grow" />
      <Button
        icon="copy"
        onClick={() => {
          onWizard({ source: s, duplicate: true });
        }}
      >
        Duplicate
      </Button>
      <Button
        variant="primary"
        icon="edit"
        disabled={s.status !== 'ACTIVE'}
        onClick={() => {
          onWizard({ source: s, duplicate: false });
        }}
      >
        Edit
      </Button>
    </>
  );
  return (
    <Drawer
      open
      wide
      onClose={onClose}
      title={s ? `${s.name} shift` : code}
      sub={s ? `${s.code} · ${term(RECORD_STATUS, s.status)?.label ?? s.status}` : undefined}
      footer={footer ?? undefined}
    >
      {detail && s ? (
        <>
          <ShiftPreview shift={s.today} colour={s.colour} />
          <h3 className="mt-16 mb-8">Rules today</h3>
          <KeyValueList
            pairs={[
              ['Hours', s.today.timing],
              ['Break', `${String(s.today.breakMinutes)} min (deducted when the span is 5 h or more)`],
              ['Weekly off', s.today.weeklyOffText],
              ['Late after', `${String(s.today.lateGraceMinutes)} min grace`],
              ['Early exit before', `${String(s.today.earlyGraceMinutes)} min grace before end`],
              ['Half day below', `${String(s.today.halfDayBelowHours)} h worked`],
              ['Absent below', `${String(s.today.absentBelowHours)} h worked`],
              ['Check-in window', `opens ${String(s.today.windowBeforeHours)} h before start`],
              ['Day close', `${String(s.today.dayCloseHours)} h after end`],
            ]}
          />
          <h3 className="mt-16 mb-8">Versions</h3>
          <ScrollArea className="dt-wrap" label="Versions">
            <table className="dt compact">
              <thead>
                <tr>
                  <th>Effective from</th>
                  <th>Hours</th>
                  <th>Changed by</th>
                  <th>Note</th>
                </tr>
              </thead>
              <tbody>
                {[...detail.versions]
                  .sort((a, b) => b.rules.effectiveFrom.localeCompare(a.rules.effectiveFrom))
                  .map(v => (
                    <tr key={v.rules.effectiveFrom}>
                      <td className="nowrap">
                        {fmtDate(v.rules.effectiveFrom)}
                        {v.state === 'SCHEDULED' && (
                          <>
                            {' '}
                            <Chip tone="blue">Scheduled</Chip>
                          </>
                        )}
                        {v.state === 'CURRENT' && (
                          <>
                            {' '}
                            <Chip tone="green">Current</Chip>
                          </>
                        )}
                      </td>
                      <td className="nowrap">{v.rules.timing}</td>
                      <td>{v.editedBy ?? 'System'}</td>
                      <td className="small">{v.note ?? ''}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </ScrollArea>
          <h3 className="mt-16 mb-8">Allocations</h3>
          {detail.allocations.length > 0 ? (
            <ScrollArea className="dt-wrap" label="Allocations">
              <table className="dt compact">
                <thead>
                  <tr>
                    <th>To</th>
                    <th>From</th>
                    <th>Until</th>
                    <th>State</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.allocations.map(a => (
                    <tr key={a.code}>
                      <td>
                        <Icon name={a.targetType === 'DEPARTMENT' ? 'building' : 'user'} size="sm" /> {a.targetName}
                      </td>
                      <td>{fmtDate(a.from)}</td>
                      <td>{a.until ? fmtDate(a.until) : '—'}</td>
                      <td>{term(ALLOCATION_STATE, a.state)?.label ?? a.state}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollArea>
          ) : (
            <p className="muted small">Not allocated to anyone.</p>
          )}
          <p className="small muted mt-8">{plural(s.employeesToday, 'employee')} work this shift today.</p>
        </>
      ) : (
        <Skeleton className="sk-line" width="60%" />
      )}
    </Drawer>
  );
}
