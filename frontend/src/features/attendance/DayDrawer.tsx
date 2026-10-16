/**
 * The prototype's day drawer (openDayDrawer): one shift day evaluated now by the server's engine: status, check-in,
 * check-out and worked time, the shift, the timeline chart, every crossing with its rule outcome among the shift's
 * markers (with Flip and Void), and the HRMS punches.
 */
import type { ReactNode } from 'react';
import { useDayDetail, type DayDetail } from '@/api/attendance';
import { DayTimeline } from '@/components/charts/DayTimeline';
import {
  AttendanceStatusChip,
  DirectionChip,
  EffectChip,
  HrmsStatusChip,
  MethodLabel,
} from '@/components/domain/Chips';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { Drawer } from '@/components/feedback/Drawer';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { Stat, StatStrip } from '@/components/ui/StatStrip';
import { cx } from '@/lib/cx';
import { DASH, fmtDate, fmtDay, fmtDuration, fmtTime, toMillis } from '@/lib/format';
import { useNeedsPermissionText, usePermissionCheck } from '@/lib/permissions';
import { useNow } from '@/lib/useNow';
import { ATTENDANCE_STATUS, term } from '@/lib/vocabulary';
import { useCorrectionFlow } from './useCorrection';
import '@/components/charts/Legend.module.css';
import '@/components/data/DataTable.module.css';
import '@/components/domain/Timeline.module.css';

const MISSING = new Set(['MISSING_CHECK_OUT', 'MISSING_CHECK_IN']);

function Body({ d, onOpenEvent }: { d: DayDetail; onOpenEvent: (code: string) => void }) {
  const can = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const correct = useCorrectionFlow();
  const now = useNow(60_000);
  const fix = can('attendance.edit');
  const t = d.times;
  const items: { at: number; marker: boolean; node: ReactNode }[] = [
    ...d.markers.map(m => ({
      at: toMillis(m.at),
      marker: true,
      node: (
        <div key={`m-${m.at}-${m.label}`} className="tl-item raw">
          <div className="xs muted">
            <span className="num">{fmtTime(m.at)}</span> · {m.label}
          </div>
        </div>
      ),
    })),
    ...d.crossings.map(c => {
      const ignored = !c.effect || c.effect.startsWith('IGNORED') || c.effect === 'VOIDED';
      return {
        at: toMillis(c.at),
        marker: false,
        node: (
          <div key={c.code} className={cx('tl-item', ignored && 'void')}>
            <div className="row wrap gap-4">
              <DirectionChip direction={c.direction} />
              <b className="num">{fmtTime(c.at, true)}</b>
              <MethodLabel method={c.method} />
              <EffectChip effect={c.effect ?? 'MOVEMENT'} />
              <span className="grow" />
              {can('events.view') && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    onOpenEvent(c.code);
                  }}
                >
                  Details
                </Button>
              )}
              {!c.voided && fix && (
                <>
                  <Button
                    size="sm"
                    icon="arrowUpDown"
                    aria-label={`Flip direction of ${c.direction} at ${fmtTime(c.at)}`}
                    onClick={() => {
                      void correct(
                        { code: c.code, direction: c.direction, at: c.at, employeeName: d.employee.name },
                        'FLIP',
                      );
                    }}
                  >
                    Flip
                  </Button>
                  <Button
                    size="sm"
                    icon="ban"
                    aria-label={`Void ${c.direction} at ${fmtTime(c.at)}`}
                    onClick={() => {
                      void correct(
                        { code: c.code, direction: c.direction, at: c.at, employeeName: d.employee.name },
                        'VOID',
                      );
                    }}
                  >
                    Void
                  </Button>
                </>
              )}
            </div>
            <div className="small muted-2 mt-4">{c.reason}</div>
          </div>
        ),
      };
    }),
  ].sort((a, b) => a.at - b.at || (a.marker ? -1 : 0) - (b.marker ? -1 : 0));
  const status = term(ATTENDANCE_STATUS, d.status);

  return (
    <>
      <div className="row between wrap gap-8 mb-12">
        <PersonCell
          id={d.employee.id}
          name={d.employee.name}
          sub={
            <>
              <EmployeeIdText employeeId={d.employee.employeeId} /> · {d.employee.department?.name ?? DASH}
            </>
          }
        />
        <AttendanceStatusChip status={d.status} tentative={d.status === 'CHECKED_OUT' && !d.finalised} late={d.late} />
      </div>
      <StatStrip className="mb-12">
        <Stat
          label="Check-in"
          value={d.checkIn ? fmtTime(d.checkIn) : DASH}
          note={
            d.late &&
            d.lateMinutes !== null && (
              <div className="xs" style={{ color: 'var(--amber)' }}>
                late by {fmtDuration(d.lateMinutes)}
              </div>
            )
          }
        />
        <Stat
          label="Check-out"
          value={d.checkOut ? fmtTime(d.checkOut) : DASH}
          note={
            <div className="xs muted">
              {d.checkOut
                ? d.checkOutFinal
                  ? 'final'
                  : `tentative until ${fmtTime(t.close)}`
                : d.missingCheckOut
                  ? 'missing'
                  : ''}
            </div>
          }
        />
        <Stat
          label="Worked"
          value={fmtDuration(d.workedMinutes)}
          note={<div className="xs muted">{d.workedHours === 'INSIDE' ? 'time inside' : 'first IN to last OUT'}</div>}
        />
      </StatStrip>
      <KeyValueList
        pairs={[
          ['Shift day', fmtDay(d.day)],
          [
            'Shift',
            <>
              <span className="row gap-4">
                <span className="sw" style={{ background: d.shift.colour }} />
                {d.shift.name} · {d.shift.timing}
              </span>
              <div className="muted xs">{d.shiftSource}</div>
            </>,
          ],
          d.holiday !== null && ['Holiday', d.holiday],
          d.weeklyOff && ['Weekly off', 'Yes'],
          ['Tracking', d.trackingLabel],
        ]}
      />
      {MISSING.has(d.status) && (
        <Callout tone="neutral" icon="sync" className="mt-12 small">
          {status?.label ?? d.status} is sent to KRZ HRMS as is. The employee or HR regularises it there.
        </Callout>
      )}
      <div className="mt-16">
        <DayTimeline
          windowStart={t.windowStart}
          start={t.start}
          end={t.end}
          close={t.close}
          finalised={d.finalised}
          now={now}
          crossings={d.crossings.map(c => ({
            at: c.at,
            direction: c.direction,
            effect: c.effect,
            label: c.effect ?? '',
          }))}
        />
      </div>
      <h3 className="mt-16 mb-8">Crossings and rule outcomes</h3>
      {d.crossings.length > 0 ? (
        <div className="timeline">{items.map(i => i.node)}</div>
      ) : (
        <p className="muted small">No crossings this shift day.</p>
      )}
      {!fix && d.crossings.length > 0 && (
        <p className="muted small">{needs('attendance.edit')} to void or flip a punch.</p>
      )}
      <h3 className="mt-16 mb-8">KRZ HRMS</h3>
      {d.punches.length > 0 ? (
        <table className="dt">
          <thead>
            <tr>
              <th>Punch</th>
              <th>Type</th>
              <th>Time</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {d.punches.map(p => (
              <tr key={p.punchId + p.kind}>
                <td className="mono">{p.punchId}</td>
                <td>{p.kind === 'VOID' ? 'VOID' : (p.type ?? '').replace('_', '-')}</td>
                <td className="num">{p.kind === 'VOID' || !p.at ? DASH : fmtTime(p.at)}</td>
                <td>
                  <HrmsStatusChip status={p.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted small">
          {d.tracked
            ? d.finalised || d.checkIn
              ? 'Nothing to send yet.'
              : 'Nothing to send — no check-in.'
            : 'Not tracked by camera — HR records attendance in KRZ HRMS.'}
        </p>
      )}
    </>
  );
}

export function DayDrawer({
  employeeId,
  day,
  onClose,
  onOpenEvent,
}: {
  employeeId: string;
  day: string;
  onClose: () => void;
  onOpenEvent: (code: string) => void;
}) {
  const detail = useDayDetail(employeeId, day);
  const can = usePermissionCheck();
  const d = detail.data;
  return (
    <Drawer
      open
      wide
      onClose={onClose}
      title={d ? `${d.employee.name} · ${fmtDate(day)}` : fmtDate(day)}
      sub="Shift day timeline"
      footer={
        <>
          {can('events.view') && (
            <ButtonLink
              icon="list"
              iconSize="default"
              to={`/events?employee=${employeeId}&day=${day}`}
              onClick={onClose}
            >
              Open in Events
            </ButtonLink>
          )}
          {can('people.view') && (
            <ButtonLink icon="user" iconSize="default" to={`/employees/${employeeId}`} onClick={onClose}>
              Employee profile
            </ButtonLink>
          )}
        </>
      }
    >
      {d ? (
        <Body d={d} onOpenEvent={onOpenEvent} />
      ) : detail.isError ? (
        <ErrorState
          what="this day"
          onRetry={() => {
            void detail.refetch();
          }}
        />
      ) : (
        <SkeletonRows rows={8} columns={3} />
      )}
    </Drawer>
  );
}
