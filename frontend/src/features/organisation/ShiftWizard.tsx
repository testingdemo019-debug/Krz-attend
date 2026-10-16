/**
 * The prototype's openShiftWizard: Basics (name, code, hours, break, colour, weekly offs) → Rules (grace, half day,
 * absent, check-in window, day close) → Review. A new or duplicated shift is created (and, if ticked, allocated
 * right after); editing saves a new version from a chosen date with the reason, and a date today or earlier needs the
 * recalculation confirmed (R10). Each step is checked when you go on, as in the prototype.
 */
import { useState } from 'react';
import { useDetailDrawers } from '@/app/DetailDrawers';
import {
  useAddShiftVersion,
  useCreateShift,
  useRecalculationImpact,
  useShifts,
  useUpdateShift,
  type RulesRequest,
  type ShiftCard,
} from '@/api/organisation';
import { ShiftPreview } from '@/components/domain/ShiftPreview';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { SwatchPicker } from '@/components/form/SwatchPicker';
import { TimeField } from '@/components/form/TimeField';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { Steps } from '@/components/ui/Steps';
import { addDays, fmtDate, fmtShiftTiming, fmtWeeklyOffs, todayDay, WEEKDAY_LABELS, WEEKDAY_ORDER } from '@/lib/format';
import { plural } from '@/lib/text';
import { SHIFT_COLOURS } from '@/lib/vocabulary';
import { messages, shiftBasicsSchema, shiftHours, shiftRulesSchema, shiftVersionSchema } from './schemas';
import '@/components/form/Field.module.css';

export interface ShiftWizardStart {
  source: ShiftCard | null;
  duplicate: boolean;
}

const STEPS = ['Basics', 'Rules', 'Review'] as const;

interface Draft {
  name: string;
  code: string;
  colour: string;
  start: string;
  end: string;
  breakMinutes: number;
  weeklyOffs: number[];
  lateGraceMinutes: number;
  earlyGraceMinutes: number;
  halfDayBelowHours: number;
  absentBelowHours: number;
  windowBeforeHours: number;
  dayCloseHours: number;
  from: string;
  note: string;
  recalc: boolean;
  allocateNext: boolean;
}

const DEFAULT_RULES = {
  start: '09:30',
  end: '18:30',
  breakMinutes: 60,
  weeklyOffs: [0, 6],
  lateGraceMinutes: 15,
  earlyGraceMinutes: 15,
  halfDayBelowHours: 4.5,
  absentBelowHours: 2,
  windowBeforeHours: 3,
  dayCloseHours: 4,
};

/** A number field with its unit (the prototype's num()). */
function NumField({
  id,
  label,
  value,
  unit,
  min,
  max,
  step = 1,
  help,
  error,
  onChange,
}: {
  id: string;
  label: string;
  value: number;
  unit: string;
  min: number;
  max: number;
  step?: number;
  help?: string;
  error?: string | undefined;
  onChange: (value: number) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <div className="input-group">
        <input
          id={id}
          type="number"
          className="input"
          value={value}
          min={min}
          max={max}
          step={step}
          onChange={event => {
            onChange(Number(event.target.value));
          }}
        />
        <span>{unit}</span>
      </div>
      {help && <div className="help">{help}</div>}
      <div className="err-text">{error ?? ''}</div>
    </div>
  );
}

export function ShiftWizard({ start, onClose }: { start: ShiftWizardStart; onClose: () => void }) {
  const { source, duplicate } = start;
  const editing = source !== null && !duplicate;
  const today = todayDay();
  const shifts = useShifts().data ?? [];
  const { openAllocation } = useDetailDrawers();
  const create = useCreateShift();
  const update = useUpdateShift(source?.code ?? '');
  const addVersion = useAddShiftVersion(source?.code ?? '');
  const [step, setStep] = useState(1);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [d, setD] = useState<Draft>(() => {
    const base = source
      ? {
          start: source.today.start,
          end: source.today.end,
          breakMinutes: source.today.breakMinutes,
          weeklyOffs: [...source.today.weeklyOffs],
          lateGraceMinutes: source.today.lateGraceMinutes,
          earlyGraceMinutes: source.today.earlyGraceMinutes,
          halfDayBelowHours: source.today.halfDayBelowHours,
          absentBelowHours: source.today.absentBelowHours,
          windowBeforeHours: source.today.windowBeforeHours,
          dayCloseHours: source.today.dayCloseHours,
        }
      : DEFAULT_RULES;
    const free = SHIFT_COLOURS.find(c => !shifts.some(x => x.colour === c)) ?? SHIFT_COLOURS[0];
    return {
      ...base,
      name: duplicate && source ? `${source.name} (copy)` : (source?.name ?? ''),
      code: duplicate ? '' : (source?.code ?? ''),
      colour: source && !duplicate ? source.colour : free,
      from: editing ? addDays(today, 1) : today,
      note: '',
      recalc: false,
      allocateNext: true,
    };
  });
  const set = <K extends keyof Draft>(key: K, value: Draft[K]): void => {
    setD(prev => ({ ...prev, [key]: value }));
  };
  const impact = useRecalculationImpact(editing && step === 3 ? source.code : null, d.from).data;
  const lengthHours = shiftHours(d.start, d.end);

  const check = (): boolean => {
    const others = shifts.filter(x => x.code !== source?.code || duplicate);
    const taken = {
      names: (editing ? others.filter(x => x.code !== source.code) : others).map(x => x.name.toLowerCase()),
      codes: shifts.map(x => x.code),
    };
    const result =
      step === 1
        ? shiftBasicsSchema(taken, editing).safeParse(d)
        : step === 2
          ? shiftRulesSchema(lengthHours).safeParse(d)
          : editing
            ? shiftVersionSchema.safeParse({ ...d, effectiveFrom: d.from, today })
            : { success: true as const };
    const found = messages(result);
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const rules: RulesRequest = {
    start: d.start,
    end: d.end,
    breakMinutes: d.breakMinutes,
    weeklyOffs: [...d.weeklyOffs].sort(),
    lateGraceMinutes: d.lateGraceMinutes,
    earlyGraceMinutes: d.earlyGraceMinutes,
    halfDayBelowHours: d.halfDayBelowHours,
    absentBelowHours: d.absentBelowHours,
    windowBeforeHours: d.windowBeforeHours,
    dayCloseHours: d.dayCloseHours,
  };

  const save = async (): Promise<void> => {
    if (!check()) return;
    try {
      if (editing) {
        if (d.name.trim() !== source.name || d.colour !== source.colour) {
          await update.mutateAsync({ name: d.name.trim(), colour: d.colour, version: source.version });
        }
        await addVersion.mutateAsync({
          effectiveFrom: d.from,
          rules,
          note: d.note.trim(),
          confirmRecalculation: d.recalc,
        });
        onClose();
        toast(`${d.name.trim()} updated from ${fmtDate(d.from)}`, { type: 'success', logged: true });
      } else {
        const created = await create.mutateAsync({
          code: d.code.trim().toUpperCase(),
          name: d.name.trim(),
          colour: d.colour,
          effectiveFrom: null,
          rules,
        });
        const code = created.shift.code;
        onClose();
        toast(`${created.shift.name} created`, {
          type: 'success',
          logged: true,
          ...(d.allocateNext
            ? {}
            : {
                action: {
                  label: 'Allocate now',
                  run: () => {
                    openAllocation({ shiftCode: code });
                  },
                },
              }),
        });
        if (d.allocateNext) openAllocation({ shiftCode: code });
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : 'The shift could not be saved', { type: 'error' });
    }
  };

  const preview = <ShiftPreview shift={rules} colour={d.colour} />;
  const pending = create.isPending || update.isPending || addVersion.isPending;
  return (
    <Modal
      open
      size="lg"
      title={editing ? `Edit ${source.name}` : duplicate && source ? `Duplicate ${source.name}` : 'New shift'}
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
            <Button
              variant="primary"
              onClick={() => {
                if (check()) setStep(s => s + 1);
              }}
            >
              Next
              <Icon name="arrowRight" />
            </Button>
          ) : (
            <Button variant="primary" pending={pending} onClick={() => void save()}>
              {editing ? 'Save new version' : 'Create shift'}
            </Button>
          )}
        </>
      }
    >
      <Steps className="row gap-8 mb-16" names={STEPS} step={step} list />
      {step === 1 && (
        <div className="form-grid">
          <div className="field">
            <label htmlFor="sw-name">Name</label>
            <input
              id="sw-name"
              className="input"
              placeholder="e.g. Warehouse Morning"
              value={d.name}
              onChange={event => {
                set('name', event.target.value);
              }}
            />
            <div className="err-text">{errors.name ?? ''}</div>
          </div>
          <div className="field">
            <label htmlFor="sw-code">Code</label>
            <input
              id="sw-code"
              className="input mono"
              maxLength={8}
              placeholder="WH-M"
              disabled={editing}
              value={d.code}
              onChange={event => {
                set('code', event.target.value);
              }}
            />
            <div className="help">
              {editing ? 'The code can’t change once created.' : 'Capital letters, digits and dashes.'}
            </div>
            <div className="err-text">{errors.code ?? ''}</div>
          </div>
          <div className="field">
            <span className="label">Start</span>
            <TimeField
              value={d.start}
              label="Start"
              onChange={hm => {
                set('start', hm);
              }}
            />
          </div>
          <div className="field">
            <span className="label">End</span>
            <TimeField
              value={d.end}
              label="End"
              onChange={hm => {
                set('end', hm);
              }}
            />
            <div className="help">An end before the start means the shift ends the next day (+1 day).</div>
          </div>
          <NumField
            id="sw-break"
            label="Unpaid break"
            value={d.breakMinutes}
            unit="min"
            min={0}
            max={120}
            step={5}
            help="Deducted from worked hours when the span is 5 h or more."
            error={errors.breakMinutes}
            onChange={v => {
              set('breakMinutes', v);
            }}
          />
          <div className="field">
            <span className="label">Colour</span>
            <SwatchPicker
              colours={SHIFT_COLOURS}
              value={d.colour}
              name="sw-colour"
              onChange={c => {
                set('colour', c);
              }}
            />
          </div>
          <fieldset className="field full" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="small" style={{ fontWeight: 600, marginBottom: 6 }}>
              Weekly offs
            </legend>
            <div className="row wrap gap-4">
              {WEEKDAY_ORDER.map(w => (
                <label
                  key={w}
                  className="check"
                  style={{ border: '1px solid var(--border)', borderRadius: 6, padding: '4px 8px' }}
                >
                  <input
                    type="checkbox"
                    checked={d.weeklyOffs.includes(w)}
                    onChange={event => {
                      set(
                        'weeklyOffs',
                        event.target.checked ? [...d.weeklyOffs, w] : d.weeklyOffs.filter(x => x !== w),
                      );
                    }}
                  />
                  {WEEKDAY_LABELS[w]}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="full">{preview}</div>
          <div className="err-text full">{errors.timing ?? ''}</div>
        </div>
      )}
      {step === 2 && (
        <div className="form-grid">
          <NumField
            id="sw-late"
            label="Late after start"
            value={d.lateGraceMinutes}
            unit="min grace"
            min={0}
            max={120}
            error={errors.lateGraceMinutes}
            onChange={v => {
              set('lateGraceMinutes', v);
            }}
          />
          <NumField
            id="sw-early"
            label="Early exit before end"
            value={d.earlyGraceMinutes}
            unit="min grace"
            min={0}
            max={120}
            error={errors.earlyGraceMinutes}
            onChange={v => {
              set('earlyGraceMinutes', v);
            }}
          />
          <NumField
            id="sw-half"
            label="Half day below"
            value={d.halfDayBelowHours}
            unit="hours worked"
            min={0}
            max={12}
            step={0.5}
            error={errors.halfDayBelowHours}
            onChange={v => {
              set('halfDayBelowHours', v);
            }}
          />
          <NumField
            id="sw-absent"
            label="Absent below"
            value={d.absentBelowHours}
            unit="hours worked"
            min={0}
            max={12}
            step={0.5}
            error={errors.absentBelowHours}
            onChange={v => {
              set('absentBelowHours', v);
            }}
          />
          <NumField
            id="sw-window"
            label="Check-in window opens"
            value={d.windowBeforeHours}
            unit="h before start"
            min={1}
            max={6}
            error={errors.windowBeforeHours}
            onChange={v => {
              set('windowBeforeHours', v);
            }}
          />
          <NumField
            id="sw-close"
            label="Day close"
            value={d.dayCloseHours}
            unit="h after end"
            min={1}
            max={8}
            help="Check-outs become final at day close."
            error={errors.dayCloseHours}
            onChange={v => {
              set('dayCloseHours', v);
            }}
          />
          <div className="full">{preview}</div>
        </div>
      )}
      {step === 3 && (
        <>
          <div className="row gap-8 mb-8">
            <span className="sw" style={{ background: d.colour, width: 16, height: 16 }} />
            <b>{d.name}</b>
            <span className="mono muted small">{d.code.toUpperCase()}</span>
          </div>
          {preview}
          <div className="mt-12">
            <KeyValueList
              pairs={[
                ['Hours', fmtShiftTiming(d.start, d.end)],
                ['Unpaid break', `${String(d.breakMinutes)} min`],
                ['Weekly offs', fmtWeeklyOffs(d.weeklyOffs)],
                ['Late after', `${String(d.lateGraceMinutes)} min`],
                ['Early exit before end', `${String(d.earlyGraceMinutes)} min`],
                ['Half day below', `${String(d.halfDayBelowHours)} h`],
                ['Absent below', `${String(d.absentBelowHours)} h`],
                ['Window / day close', `${String(d.windowBeforeHours)} h before · ${String(d.dayCloseHours)} h after`],
              ]}
            />
          </div>
          {editing ? (
            <>
              <div className="form-grid mt-16">
                <div className="field">
                  <label htmlFor="sw-from">Effective from</label>
                  <DateField
                    id="sw-from"
                    value={d.from}
                    label="Effective from"
                    min={addDays(today, -21)}
                    onChange={day => {
                      setD(prev => ({ ...prev, from: day, recalc: false }));
                    }}
                  />
                  <div className="help">Days before this keep the current rules.</div>
                </div>
                <div className="field">
                  <label htmlFor="sw-note">Reason for the change</label>
                  <input
                    id="sw-note"
                    className="input"
                    placeholder="e.g. Office opens earlier from November"
                    value={d.note}
                    onChange={event => {
                      set('note', event.target.value);
                    }}
                  />
                  <div className="err-text">{errors.note ?? ''}</div>
                </div>
                {d.from <= today && (
                  <label className="check full callout warn" style={{ alignItems: 'flex-start' }}>
                    <input
                      type="checkbox"
                      checked={d.recalc}
                      onChange={event => {
                        set('recalc', event.target.checked);
                      }}
                    />
                    <span>
                      <b>Recalculate {d.from < today ? `${String(impact?.days ?? 0)} past days` : 'today'}</b> with the
                      new rules. Punches already sent to KRZ HRMS for those days are voided and re-sent.
                    </span>
                  </label>
                )}
                <div className="err-text full">{errors.recalc ?? ''}</div>
              </div>
              <p className="small muted mt-8">
                {plural(impact?.employees ?? source.employeesToday, 'employee')} work this shift{' '}
                {d.from <= today ? 'today' : `on ${fmtDate(d.from)}`}.
              </p>
            </>
          ) : (
            <label className="check mt-16">
              <input
                type="checkbox"
                checked={d.allocateNext}
                onChange={event => {
                  set('allocateNext', event.target.checked);
                }}
              />{' '}
              Allocate this shift right after creating it
            </label>
          )}
        </>
      )}
    </Modal>
  );
}
