/**
 * The prototype's openAllocationWizard (R9): Shift → Who (departments or employees) → Dates → Impact (the server's
 * plan: who changes shift, who keeps an individual allocation, which allocations end or are cancelled) → Confirm.
 * Opened from Shift allocation, Employees (bulk and profile), the employment editor, a department and a new shift.
 */
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useAllEnabledEmployees } from '@/api/employees';
import {
  useAllDepartments,
  useAllocationPlan,
  useCommitAllocation,
  shiftChoices,
  useShifts,
  type AllocationRequest,
} from '@/api/organisation';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { MultiSelect, type MultiSelectItem } from '@/components/form/MultiSelect';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Segmented } from '@/components/ui/Segmented';
import { StatStrip } from '@/components/ui/StatStrip';
import { Steps } from '@/components/ui/Steps';
import { addDays, fmtDate, fmtDateShort, todayDay } from '@/lib/format';
import { plural } from '@/lib/text';
import { allocationSteps, messages } from './schemas';
import '@/components/data/DataTable.module.css';
import '@/components/form/Field.module.css';

export interface AllocationStart {
  targetType?: 'DEPARTMENT' | 'EMPLOYEE';
  targetIds?: readonly string[];
  shiftCode?: string;
}

const STEPS = ['Shift', 'Who', 'Dates', 'Impact', 'Confirm'] as const;

export function AllocationWizard({ start, onClose }: { start: AllocationStart; onClose: () => void }) {
  const navigate = useNavigate();
  const here = useLocation().pathname;
  const today = todayDay();
  const shifts = shiftChoices(useShifts().data ?? []);
  const departments = (useAllDepartments().data ?? []).filter(d => d.status === 'ACTIVE');
  const employees = useAllEnabledEmployees(true).data ?? [];
  const commit = useCommitAllocation();
  const [step, setStep] = useState(start.shiftCode ? 2 : 1);
  const [targetType, setTargetType] = useState<'DEPARTMENT' | 'EMPLOYEE'>(start.targetType ?? 'DEPARTMENT');
  const [targetIds, setTargetIds] = useState<Set<string>>(new Set(start.targetIds ?? []));
  const [shiftCode, setShiftCode] = useState(start.shiftCode ?? '');
  const [from, setFrom] = useState(addDays(today, 1));
  const [until, setUntil] = useState('');
  const [note, setNote] = useState('');
  const [individualMode, setIndividualMode] = useState<'KEEP' | 'REPLACE'>('KEEP');
  const [deptFilter, setDeptFilter] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const shift = shifts.find(s => s.code === shiftCode);
  const draft: AllocationRequest = {
    targetType,
    targetIds: [...targetIds],
    shiftCode,
    from,
    until: until || null,
    individualMode,
    note: note.trim() || null,
  };
  const plan = useAllocationPlan(step >= 4 ? draft : null).data;

  const items: MultiSelectItem[] =
    targetType === 'DEPARTMENT'
      ? departments.map(d => ({
          id: d.code,
          label: d.name,
          sub: `${plural(d.employees, 'employee')} · now ${d.shift.name}`,
          search: `${d.name} ${d.code}`,
        }))
      : employees
          .filter(e => !deptFilter || e.department?.code === deptFilter)
          .map(e => ({
            id: e.id,
            label: e.name,
            sub: `${e.department?.name ?? '—'} · now ${e.shiftToday?.name ?? '—'}`,
            search: `${e.name} ${e.department?.name ?? ''} ${e.employeeId ?? ''}`,
          }));

  const next = (): void => {
    const check =
      step === 1
        ? allocationSteps[1].safeParse({ shiftCode })
        : step === 2
          ? allocationSteps[2].safeParse({ targetIds: [...targetIds] })
          : step === 3
            ? allocationSteps[3].safeParse({ from, until })
            : { success: true as const };
    const found = messages(check);
    setErrors(found);
    if (Object.keys(found).length === 0) setStep(s => s + 1);
  };

  const save = (): void => {
    commit.mutate(draft, {
      onSuccess: result => {
        onClose();
        toast(
          `${shift?.name ?? 'Shift'} allocated to ${plural(
            result.created.length,
            targetType === 'DEPARTMENT' ? 'department' : 'employee',
          )} from ${fmtDate(from)} · ${plural(result.changing, 'employee')} change shift`,
          { type: 'success', logged: true },
        );
        if (!here.startsWith('/org/allocations') && !here.startsWith('/employees')) void navigate('/org/allocations');
      },
      onError: error => toast(error.message, { type: 'error' }),
    });
  };

  const names = [...targetIds].map(id =>
    targetType === 'DEPARTMENT'
      ? (departments.find(d => d.code === id)?.name ?? id)
      : (employees.find(e => e.id === id)?.name ?? id),
  );
  const impact = [...(plan?.impact ?? [])].sort(
    (a, b) => Number(b.changes) - Number(a.changes) || a.displayName.localeCompare(b.displayName),
  );

  return (
    <Modal
      open
      size="lg"
      title="Allocate shift"
      onClose={onClose}
      footer={
        <>
          {step > 1 ? (
            <Button
              icon="arrowLeft"
              onClick={() => {
                setErrors({});
                setStep(s => s - 1);
              }}
            >
              Back
            </Button>
          ) : (
            <Button onClick={onClose}>Cancel</Button>
          )}
          <span className="grow" />
          {step < STEPS.length ? (
            <Button variant="primary" onClick={next}>
              Next
              <Icon name="arrowRight" />
            </Button>
          ) : (
            <Button variant="primary" pending={commit.isPending} onClick={save}>
              Allocate shift
            </Button>
          )}
        </>
      }
    >
      <Steps className="row wrap gap-4 mb-16" names={STEPS} step={step} />
      {step === 1 && (
        <>
          <div className="col">
            {shifts.map(s => (
              <label
                key={s.code}
                className="card pad check"
                style={{
                  cursor: 'pointer',
                  alignItems: 'flex-start',
                  ...(shiftCode === s.code
                    ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 3px var(--accent-weak)' }
                    : {}),
                }}
              >
                <input
                  type="radio"
                  name="aw-shift"
                  checked={shiftCode === s.code}
                  onChange={() => {
                    setShiftCode(s.code);
                  }}
                />
                <span className="grow row gap-8 wrap">
                  <span className="sw" style={{ background: s.colour }} />
                  <b>{s.name}</b>
                  <span className="muted small">
                    {s.today.timing} · weekly off {s.today.weeklyOffText}
                  </span>
                </span>
              </label>
            ))}
          </div>
          <div className="err-text">{errors.shiftCode ?? ''}</div>
        </>
      )}
      {step === 2 && (
        <>
          <div className="row wrap gap-8 mb-12">
            <Segmented
              label="Allocate to"
              value={targetType}
              segments={[
                { value: 'DEPARTMENT', label: 'Departments', icon: 'building' },
                { value: 'EMPLOYEE', label: 'Employees', icon: 'user' },
              ]}
              onChange={value => {
                if (value !== targetType) setTargetIds(new Set());
                setTargetType(value);
              }}
            />
            {targetType === 'EMPLOYEE' && (
              <select
                className="select"
                style={{ width: 220 }}
                aria-label="Filter by department"
                value={deptFilter}
                onChange={event => {
                  setDeptFilter(event.target.value);
                }}
              >
                <option value="">All departments</option>
                {departments.map(d => (
                  <option key={d.code} value={d.code}>
                    {d.name}
                  </option>
                ))}
              </select>
            )}
          </div>
          <p className="small muted mb-8">
            {targetType === 'DEPARTMENT'
              ? 'Everyone in the department, except people with their own allocation (you choose in the next steps).'
              : 'An individual allocation beats the department shift.'}
          </p>
          <MultiSelect
            items={items}
            selected={targetIds}
            onChange={setTargetIds}
            searchPlaceholder={targetType === 'DEPARTMENT' ? 'Search departments' : 'Search employees'}
          />
          <div className="err-text">{errors.targetIds ?? ''}</div>
        </>
      )}
      {step === 3 && (
        <div className="form-grid">
          <div className="field">
            <label htmlFor="aw-from">Effective from</label>
            <DateField id="aw-from" value={from} label="Effective from" min={today} onChange={setFrom} />
            <div className="help">Starts on this shift day. Past days keep their shift.</div>
          </div>
          <div className="field">
            <label htmlFor="aw-until">Until (optional)</label>
            <DateField
              id="aw-until"
              value={until}
              label="Until"
              min={today}
              placeholder="Open-ended"
              onChange={setUntil}
            />
            <div className="err-text">{errors.until ?? ''}</div>
          </div>
          <div className="field full">
            <label htmlFor="aw-note">Note</label>
            <input
              id="aw-note"
              className="input"
              placeholder="e.g. Night support rota for Q4"
              value={note}
              onChange={event => {
                setNote(event.target.value);
              }}
            />
          </div>
        </div>
      )}
      {step === 4 && plan && (
        <>
          <StatStrip className="mb-12">
            {(
              [
                [plan.changing, 'change shift'],
                [plan.unchanged, 'already on it'],
                [plan.overridden, 'keep their own shift'],
                [plan.conflicts.length, 'allocations adjusted'],
              ] as const
            ).map(([value, label]) => (
              <div key={label} className="stat">
                <div className="v">{value}</div>
                <div className="l">{label}</div>
              </div>
            ))}
          </StatStrip>
          {plan.individualCount > 0 && (
            <fieldset className="callout warn mb-12" style={{ display: 'block' }}>
              <legend className="small" style={{ fontWeight: 650 }}>
                {plural(plan.individualCount, 'person', 'people')} in{' '}
                {targetIds.size === 1 ? 'this department' : 'these departments'}{' '}
                {plan.individualCount === 1 ? 'has' : 'have'} an individual allocation
              </legend>
              {(
                [
                  ['KEEP', 'Keep individual', 'they stay on their own shift'],
                  ['REPLACE', 'Replace', 'end their individual allocations so they follow the department'],
                ] as const
              ).map(([value, title, text]) => (
                <label key={value} className="check mt-4">
                  <input
                    type="radio"
                    name="aw-ind"
                    checked={individualMode === value}
                    onChange={() => {
                      setIndividualMode(value);
                    }}
                  />{' '}
                  <span>
                    <b>{title}</b> — {text}
                  </span>
                </label>
              ))}
            </fieldset>
          )}
          {plan.conflicts.length > 0 && (
            <>
              <h3 className="mb-8">Overlapping allocations</h3>
              <ul className="small" style={{ margin: '0 0 12px', paddingLeft: 18 }}>
                {plan.conflicts.map(c => (
                  <li key={c.code}>
                    {c.targetName}: {c.shift.name} from {fmtDate(c.from)} {c.change}
                  </li>
                ))}
              </ul>
            </>
          )}
          <div style={{ maxHeight: 260, overflow: 'auto' }}>
            <table className="dt compact">
              <thead>
                <tr>
                  <th>Employee</th>
                  <th>Shift on {fmtDateShort(from)} now</th>
                  <th>After</th>
                </tr>
              </thead>
              <tbody>
                {impact.length > 0 ? (
                  impact.map(x => (
                    <tr key={x.employeeId}>
                      <td>{x.displayName}</td>
                      <td>{x.before.name}</td>
                      <td>
                        {x.changes ? (
                          <b>{x.after.name}</b>
                        ) : x.overridden ? (
                          <span className="muted">{x.after.name} (individual)</span>
                        ) : (
                          <span className="muted">No change</span>
                        )}
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={3} className="muted">
                      Nobody is affected.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
      {step === 5 && plan && shift && (
        <Callout tone="neutral" icon="route">
          <b>{shift.name}</b> ({shift.today.timing}) for{' '}
          {plural(targetIds.size, targetType === 'DEPARTMENT' ? 'department' : 'employee')}:{' '}
          {names.slice(0, 4).join(', ')}
          {targetIds.size > 4 ? ` and ${String(targetIds.size - 4)} more` : ''}.
          <br />
          From <b>{fmtDate(from)}</b>
          {until ? (
            <>
              {' '}
              until <b>{fmtDate(until)}</b>
            </>
          ) : (
            ', open-ended'
          )}
          . {plural(plan.changing, 'employee')} change shift
          {plan.conflicts.length > 0 ? `; ${plural(plan.conflicts.length, 'allocation')} adjusted` : ''}.
          {draft.note && (
            <>
              <br />
              Note: {draft.note}
            </>
          )}
        </Callout>
      )}
    </Modal>
  );
}
