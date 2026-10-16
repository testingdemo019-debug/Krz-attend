/**
 * Settings → Attendance rules: the rules that turn crossings into punches, and the rule tester. The tester sends a day
 * of crossings with the page's rules (saved or not) to the server, which runs the same engine as Today, Events and
 * the HRMS outbox; nothing is stored.
 */
import { useState } from 'react';
import { request } from '@/api/client';
import type { DayDetail } from '@/api/attendance';
import { useShifts } from '@/api/organisation';
import { useRuleTest, useRules, useSaveRules, type AttendanceRules, type RulesView } from '@/api/settings';
import { AttendanceStatusChip, EffectChip } from '@/components/domain/Chips';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { EmployeePicker } from '@/components/form/EmployeePicker';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { IconButton } from '@/components/ui/IconButton';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { ScrollArea } from '@/components/layout/ScrollArea';
import {
  addDays,
  dayOf,
  fmtClock,
  fmtDate,
  fmtDuration,
  fmtShiftTiming,
  fmtTime,
  todayDay,
  zonedParts,
} from '@/lib/format';
import { plural } from '@/lib/text';
import { useDraft } from '@/lib/useDraft';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'Attendance rules';
const SUB =
  'Rules that turn crossings into punches. Shift timings, grace and half-day limits are set per shift in Organisation → Shifts.';

interface Row {
  /** Seconds since the shift day's midnight. */
  sec: number;
  dir: 'IN' | 'OUT';
  next: boolean;
}

/** The tester starts with "Stepped out for lunch", as the prototype does. */
const LUNCH = {
  label: 'Stepped out for lunch',
  rows: [
    ['09:25', 'IN'],
    ['13:10', 'OUT'],
    ['13:55', 'IN'],
    ['18:35', 'OUT'],
  ],
} as const;

/** The prototype's RULE_SCENARIOS; "night" uses an overnight shift when there is one. */
const SCENARIOS: readonly { label: string; night?: true; rows: readonly (readonly [string, 'IN' | 'OUT', true?])[] }[] =
  [
    {
      label: 'Normal day',
      rows: [
        ['09:20', 'IN'],
        ['18:40', 'OUT'],
      ],
    },
    {
      label: 'Late arrival',
      rows: [
        ['10:05', 'IN'],
        ['18:45', 'OUT'],
      ],
    },
    LUNCH,
    {
      label: 'Early exit',
      rows: [
        ['09:28', 'IN'],
        ['16:20', 'OUT'],
      ],
    },
    { label: 'Missing check-out', rows: [['09:31', 'IN']] },
    { label: 'Missing check-in', rows: [['18:40', 'OUT']] },
    {
      label: 'Duplicate and turn-back',
      rows: [
        ['09:20:00', 'IN'],
        ['09:20:40', 'OUT'],
        ['09:22:10', 'IN'],
        ['09:23:00', 'IN'],
        ['18:31', 'OUT'],
      ],
    },
    {
      label: 'Night shift',
      night: true,
      rows: [
        ['20:55', 'IN'],
        ['06:10', 'OUT', true],
      ],
    },
  ];

/** "9:20 AM", "09:20" or "18:40:30" → seconds since midnight, or null (the prototype's parseClock). */
function parseClock(text: string): number | null {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?$/.exec(text.trim());
  if (!m) return null;
  let h = Number(m[1]);
  const mi = Number(m[2]);
  const s = Number(m[3] ?? 0);
  const ap = m[4]?.toUpperCase();
  if (ap) {
    if (h < 1 || h > 12) return null;
    h = (h % 12) + (ap === 'PM' ? 12 : 0);
  }
  if (h > 23 || mi > 59 || s > 59) return null;
  return h * 3600 + mi * 60 + s;
}

const hms = (sec: number): string =>
  [Math.floor(sec / 3600), Math.floor((sec % 3600) / 60), sec % 60].map(n => String(n).padStart(2, '0')).join(':');

const rowsOf = (scenario: (typeof SCENARIOS)[number]): Row[] =>
  scenario.rows.map(([time, dir, next]) => ({ sec: parseClock(time) ?? 0, dir, next: Boolean(next) }));

/** A time with its seconds only when there are some. */
const timeOf = (at: string): string => fmtTime(at, zonedParts(at).s !== 0);

export function RulesSettings() {
  const rules = useRules();
  if (rules.isError) return <ErrorState what="the settings" onRetry={() => void rules.refetch()} />;
  if (!rules.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={4} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <RulesForm
      view={rules.data}
      reload={() => {
        void rules.refetch();
      }}
    />
  );
}

function RulesForm({ view, reload }: { view: RulesView; reload: () => void }) {
  const save = useSaveRules();
  const draft = useDraft({
    title: TITLE,
    source: view.settings,
    save: d => save.mutateAsync({ ...d, version: view.version }),
    onStale: reload,
  });
  const d = draft.value;
  const select = <K extends keyof AttendanceRules>(
    key: K,
    id: string,
    label: string,
    options: readonly (readonly [AttendanceRules[K], string])[],
    help?: string,
  ) => (
    <SettingField label={label} htmlFor={id} {...(help ? { help } : {})}>
      <select
        id={id}
        className="select"
        value={String(d[key])}
        onChange={e => {
          const picked = options.find(([v]) => String(v) === e.target.value);
          if (picked) draft.set({ ...d, [key]: picked[0] });
        }}
      >
        {options.map(([value, text]) => (
          <option key={String(value)} value={String(value)}>
            {text}
          </option>
        ))}
      </select>
    </SettingField>
  );
  const minutes = (ns: readonly number[], unit: (n: number) => string) => ns.map(n => [n, unit(n)] as const);
  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="form-grid">
        {select(
          'duplicateMinutes',
          'ar-dup',
          'Ignore a repeat in the same direction within',
          minutes([1, 2, 3, 5, 10], n => `${String(n)} min`),
          'Stops one walk-in from counting twice.',
        )}
        {select(
          'turnedBackSeconds',
          'ar-tb',
          'Treat IN then OUT (or OUT then IN) within … as turned back',
          minutes([30, 60, 90, 120], n => `${String(n)} seconds`),
          'Both crossings are ignored.',
        )}
        {select(
          'absentCutoffHours',
          'ar-cut',
          'Mark absent if no check-in by',
          minutes([2, 3, 4, 5, 6], n => `${String(n)} h after shift start`),
          'Before that, the status is “Not yet in”.',
        )}
        {select('workedHours', 'ar-wh', 'Worked hours', [
          ['SPAN', 'First IN to last OUT, minus break'],
          ['INSIDE', 'Time actually inside (IN–OUT pairs)'],
        ])}
        {select(
          'pushMode',
          'ar-push',
          'Send to KRZ HRMS',
          [
            ['FINAL', 'Check-in, and final check-out after day close'],
            ['EVERY', 'Every counted crossing (movements too)'],
          ],
          'Ignored crossings are never sent.',
        )}
      </div>
      <Callout tone="neutral" icon="info" className="mt-12 small">
        Fixed rules: the first IN of the shift day is the check-in. An OUT before shift end − early-exit grace is
        “Stepped out”. The last OUT after shift end is the check-out, final at day close.
      </Callout>
      <div className="set-section mt-16">
        <h3>Rule tester</h3>
        <p className="help">
          Try a day of crossings against the rules above — including unsaved changes. It uses the same engine as Today,
          Events and the HRMS outbox.
        </p>
        <RuleTester rules={d} />
      </div>
    </SettingsCard>
  );
}

function RuleTester({ rules }: { rules: AttendanceRules }) {
  const all = useShifts().data ?? [];
  const shifts = all.filter(s => s.status === 'ACTIVE');
  const defaultCode = shifts.find(s => s.defaultShift)?.code ?? shifts[0]?.code ?? '';
  const nightCode = shifts.find(s => s.today.overnight)?.code ?? defaultCode;
  const [shiftCode, setShiftCode] = useState<string | null>(null);
  const [holiday, setHoliday] = useState(false);
  const [afterClose, setAfterClose] = useState(true);
  const [rows, setRows] = useState<Row[]>(() => rowsOf(LUNCH));
  const [loaded, setLoaded] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const shift = shiftCode ?? defaultCode;
  const test = shift
    ? {
        shiftCode: shift,
        holiday,
        afterClose,
        rows: rows.map(r => ({ time: hms(r.sec), direction: r.dir, nextDay: r.next })),
        rules,
      }
    : null;
  const result = useRuleTest(test).data;
  const update = (i: number, patch: Partial<Row>) => {
    setRows(current => current.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  };

  const loadDay = async (employeeId: string, name: string) => {
    const day = addDays(todayDay(), -1);
    try {
      const detail = await request<DayDetail>(
        `/attendance/days/${encodeURIComponent(employeeId)}/${encodeURIComponent(day)}`,
      );
      const crossings = detail.crossings.filter(c => !c.voided);
      setShiftCode(detail.shift.code);
      setRows(
        crossings.map(c => {
          const p = zonedParts(c.at);
          return {
            sec: p.h * 3600 + p.mi * 60 + p.s,
            dir: c.direction === 'OUT' ? 'OUT' : 'IN',
            next: dayOf(c.at) > day,
          };
        }),
      );
      setHoliday(false);
      setLoaded(`${name} · ${fmtDate(day)}`);
      toast(`Loaded ${plural(crossings.length, 'crossing')} for ${name}`, { type: 'success' });
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), { type: 'error' });
    }
  };

  return (
    <div className="mt-12">
      <div className="row wrap gap-8 mb-12">
        {SCENARIOS.map(s => (
          <Button
            key={s.label}
            size="sm"
            onClick={() => {
              setShiftCode(s.night ? nightCode : defaultCode);
              setRows(rowsOf(s));
              setHoliday(false);
              setLoaded(null);
            }}
          >
            {s.label}
          </Button>
        ))}
        <Button
          size="sm"
          variant="ghost"
          icon="user"
          onClick={() => {
            setPicking(true);
          }}
        >
          Load a real day…
        </Button>
      </div>
      <div className="row wrap gap-12 mb-12">
        <label className="small" htmlFor="rt-shift">
          Shift
        </label>
        <select
          id="rt-shift"
          className="select"
          style={{ width: 'auto' }}
          value={shift}
          onChange={e => {
            setShiftCode(e.target.value);
          }}
        >
          {shifts.map(s => (
            <option key={s.code} value={s.code}>
              {s.name} · {fmtShiftTiming(s.today.start, s.today.end)}
            </option>
          ))}
        </select>
        <label className="small" htmlFor="rt-close">
          Evaluate
        </label>
        <select
          id="rt-close"
          className="select"
          style={{ width: 'auto' }}
          value={afterClose ? 'close' : 'live'}
          onChange={e => {
            setAfterClose(e.target.value === 'close');
          }}
        >
          <option value="close">After day close (final)</option>
          <option value="live">Right after the last crossing (live)</option>
        </select>
        <label className="check small">
          <input
            type="checkbox"
            id="rt-hol"
            checked={holiday}
            onChange={e => {
              setHoliday(e.target.checked);
            }}
          />{' '}
          Public holiday
        </label>
        {loaded && <span className="tag">From {loaded}</span>}
      </div>
      <div className="col" style={{ gap: 16 }}>
        <ScrollArea className="dt-wrap" label="Crossings to test">
          <table className="dt compact">
            <thead>
              <tr>
                <th>Time</th>
                <th>Next day</th>
                <th>Direction</th>
                <th>Effect</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.length ? (
                rows.map((r, i) => {
                  const effect = result?.rows[i];
                  return (
                    <tr key={i}>
                      <td>
                        <input
                          key={`${String(i)}-${String(r.sec)}`}
                          className="input mono"
                          style={{ width: 120, height: 30 }}
                          defaultValue={fmtClock(r.sec)}
                          aria-label={`Time of crossing ${String(i + 1)}`}
                          onBlur={e => {
                            const sec = parseClock(e.target.value);
                            if (sec === null) {
                              e.target.setAttribute('aria-invalid', 'true');
                              toast('Use a time like 9:20 AM, 09:20 or 18:40:30', { type: 'warn' });
                            } else {
                              e.target.removeAttribute('aria-invalid');
                              update(i, { sec });
                            }
                          }}
                          onKeyDown={e => {
                            if (e.key === 'Enter') e.currentTarget.blur();
                          }}
                        />
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          checked={r.next}
                          aria-label={`Crossing ${String(i + 1)} is on the next day`}
                          onChange={e => {
                            update(i, { next: e.target.checked });
                          }}
                        />
                      </td>
                      <td>
                        <select
                          className="select"
                          style={{ width: 84, height: 30 }}
                          value={r.dir}
                          aria-label={`Direction of crossing ${String(i + 1)}`}
                          onChange={e => {
                            update(i, { dir: e.target.value === 'OUT' ? 'OUT' : 'IN' });
                          }}
                        >
                          <option>IN</option>
                          <option>OUT</option>
                        </select>
                      </td>
                      <td>
                        <EffectChip effect={effect?.effect ?? null} />
                        <div className="muted xs">{effect?.reason ?? ''}</div>
                      </td>
                      <td>
                        <IconButton
                          icon="x"
                          size="sm"
                          label={`Remove crossing ${String(i + 1)}`}
                          onClick={() => {
                            setRows(current => current.filter((_, j) => j !== i));
                          }}
                        />
                      </td>
                    </tr>
                  );
                })
              ) : (
                <tr>
                  <td colSpan={5} className="muted">
                    No crossings — add one.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Button
            size="sm"
            icon="plus"
            className="mt-8"
            onClick={() => {
              setRows(current => {
                const last = current[current.length - 1];
                return [
                  ...current,
                  {
                    sec: last ? Math.min(86_399, last.sec + 1800) : 9 * 3600,
                    dir: last?.dir === 'IN' ? 'OUT' : 'IN',
                    next: last ? last.next : false,
                  },
                ];
              });
            }}
          >
            Add crossing
          </Button>
        </ScrollArea>
        <div className="card pad">
          <div className="row between">
            <b>Result</b>
            {result && (
              <AttendanceStatusChip
                status={result.status}
                tentative={result.status === 'CHECKED_OUT' && !result.finalised}
                late={result.late}
              />
            )}
          </div>
          {result && (
            <>
              <KeyValueList
                pairs={[
                  ['Check-in', result.checkIn ? timeOf(result.checkIn) : '—'],
                  [
                    'Check-out',
                    result.checkOut
                      ? `${timeOf(result.checkOut)} ${result.checkOutFinal ? '(final)' : '(tentative)'}`
                      : result.missingCheckOut
                        ? 'Missing'
                        : '—',
                  ],
                  ['Worked', fmtDuration(result.workedMinutes)],
                  ['Late', result.late ? 'Yes' : 'No'],
                  ['Early exit', result.earlyExit ? 'Yes' : 'No'],
                ]}
              />
              <div className="small mt-8">
                <b>Sent to KRZ HRMS:</b>{' '}
                {result.punches.length
                  ? result.punches.map(p => `${p.type.replace('_', '-')} ${fmtTime(p.at)}`).join(', ')
                  : 'nothing'}
              </div>
              <div className="muted xs mt-8">
                Shift {result.shift.name} {result.shift.timing} · late after {fmtTime(result.lateAfter)} · early exit
                before {fmtTime(result.earlyLimit)} · day close {fmtTime(result.times.close)}
              </div>
            </>
          )}
        </div>
      </div>
      <EmployeePicker
        open={picking}
        title="Load a real day"
        sub="Copies yesterday’s crossings for this employee into the tester. Nothing is changed."
        hide={e => e.trackingMode !== 'TRACKED'}
        onClose={() => {
          setPicking(false);
        }}
        onPick={e => {
          setPicking(false);
          void loadDay(e.id, e.name);
        }}
      />
    </div>
  );
}
