/**
 * The prototype's crossing drawer (openEventDrawer): who and what the rules did, the crossing's facts, its
 * attendance day, the HRMS punches and the changes, with Flip and Void for attendance.edit.
 */
import { useEffect } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client';
import { useCrossing, type EventDetail } from '@/api/crossings';
import {
  AttendanceStatusChip,
  ConfidenceBadge,
  DecisionText,
  DirectionChip,
  EffectChip,
  HrmsStatusChip,
  LivenessBadge,
  MethodLabel,
} from '@/components/domain/Chips';
import { CropImage, FrameThumb } from '@/components/domain/CropImage';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { QualityChips } from '@/components/domain/QualityChips';
import { Drawer } from '@/components/feedback/Drawer';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button, ButtonLink } from '@/components/ui/Button';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { copyText } from '@/lib/clipboard';
import { trackName } from '@/lib/text';
import { fmtDate, fmtDateTime, fmtDay, fmtTime, zoneLabel } from '@/lib/format';
import { usePermissionCheck, useNeedsPermissionText } from '@/lib/permissions';
import { useCorrectionFlow } from '@/features/attendance/useCorrection';
import '@/components/data/DataTable.module.css';
import '@/components/domain/Timeline.module.css';

function Body({ d, onOpenDay }: { d: EventDetail; onOpenDay: (employeeId: string, day: string) => void }) {
  const can = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const ev = d.event;
  const person = ev.person;
  const back = !ev.faceCrop && ev.bodyCrop;
  return (
    <>
      <div className="grid" style={{ gridTemplateColumns: '96px minmax(0,1fr)', gap: 14, alignItems: 'start' }}>
        <div>
          <CropImage
            crossing={ev.code}
            kind={back ? 'body' : 'face'}
            size="lg"
            available={!ev.cropsPurged && (ev.faceCrop || ev.bodyCrop)}
          />
          <div className="muted xs mt-4">{back ? 'Back view (Re-ID)' : 'Face crop'}</div>
        </div>
        <div className="col">
          {person ? (
            <PersonCell
              id={person.id}
              name={person.name}
              sub={
                <>
                  <EmployeeIdText employeeId={person.employeeId} /> · {d.department ?? '—'}
                </>
              }
            />
          ) : (
            <b>{ev.unknownLabel ?? 'Unknown face'}</b>
          )}
          <div className="row wrap gap-4">
            <DirectionChip direction={ev.direction} />
            <EffectChip effect={ev.effect} />
          </div>
          <div className="small muted-2">{ev.effectReason}</div>
        </div>
      </div>
      <FrameThumb crossing={ev.code} available={!ev.cropsPurged && ev.bodyCrop} className="mt-16" />
      <h3 className="mt-16 mb-8">Crossing</h3>
      <KeyValueList
        pairs={[
          [
            'Event ID',
            <>
              <span className="mono">{ev.code}</span>{' '}
              <Button
                size="sm"
                variant="ghost"
                icon="copy"
                aria-label="Copy event ID"
                onClick={() => {
                  void copyText(ev.code);
                }}
              />
            </>,
          ],
          ['Time', `${fmtDate(ev.occurredAt)}, ${fmtTime(ev.occurredAt, true)} ${zoneLabel()}`],
          [
            'Camera',
            <>
              {ev.camera.name}{' '}
              <span className="muted small">
                {ev.camera.code} · {d.entryPoint}
              </span>
            </>,
          ],
          [
            'Direction',
            <>
              {ev.direction}{' '}
              <span className="muted small">
                {d.flip && ev.flippedFrom
                  ? `(corrected from ${ev.flippedFrom} by ${d.flip.by}: ${d.flip.reason})`
                  : back
                    ? 'walking away from the camera'
                    : 'facing the camera'}
              </span>
            </>,
          ],
          ['Method', <MethodLabel key="m" method={ev.method} />],
          d.faceMatch && [
            'Face match',
            <ConfidenceBadge key="f" band={d.faceMatch.confidence} score={d.faceMatch.score} />,
          ],
          d.bodyMatch && [
            'Body match (Re-ID)',
            <>
              <ConfidenceBadge band={d.bodyMatch.confidence} score={d.bodyMatch.score} />
              {d.matchedIn && <span className="muted small"> matched to the IN at {fmtTime(d.matchedIn)}</span>}
            </>,
          ],
          ['Liveness', <LivenessBadge key="l" result={ev.liveness} />],
          [
            'Decision',
            <>
              <DecisionText decision={ev.decision} />
              {d.resolvedBy && <span className="muted small"> by {d.resolvedBy}</span>}
            </>,
          ],
          d.review && [
            'Review item',
            can('review.resolve') ? (
              <>
                <Link to={`/review?item=${d.review.code}`} className="mono">
                  {d.review.code}
                </Link>{' '}
                <span className="muted small">{d.review.status.toLowerCase()}</span>
              </>
            ) : (
              <span className="mono">{d.review.code}</span>
            ),
          ],
          [
            'Track',
            <span key="t" className="mono">
              {d.trackId === null ? '—' : trackName(d.trackId)}
            </span>,
          ],
        ]}
      />
      {d.quality && (
        <div className="mt-8">
          <QualityChips quality={d.quality} />
        </div>
      )}
      {person && d.attendance && (
        <>
          <h3 className="mt-16 mb-8">Attendance</h3>
          <KeyValueList
            pairs={[
              ['Shift day', `${fmtDay(d.attendance.shiftDay)} · ${d.attendance.shiftName} ${d.attendance.shiftTiming}`],
              [
                'Day status',
                <AttendanceStatusChip key="s" status={d.attendance.status} tentative={!d.attendance.finalised} />,
              ],
              ['Tracking', d.attendance.trackingLabel ?? '—'],
            ]}
          />
          {can('attendance.view.all') && (
            <Button
              size="sm"
              icon="calendar"
              className="mt-8"
              onClick={() => {
                if (d.attendance) onOpenDay(person.id, d.attendance.shiftDay);
              }}
            >
              Open day timeline
            </Button>
          )}
        </>
      )}
      {d.punches.length > 0 && (
        <>
          <h3 className="mt-16 mb-8">KRZ HRMS</h3>
          <table className="dt">
            <thead>
              <tr>
                <th>Punch</th>
                <th>Type</th>
                <th>Status</th>
                <th>Last attempt</th>
              </tr>
            </thead>
            <tbody>
              {d.punches.map(p => (
                <tr key={p.punchId + p.kind}>
                  <td className="mono">{p.punchId}</td>
                  <td>{p.kind === 'VOID' ? 'VOID' : p.type}</td>
                  <td>
                    <HrmsStatusChip status={p.status} />
                  </td>
                  <td className="small">{p.lastAttemptAt ? fmtDateTime(p.lastAttemptAt) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {d.changes.length > 0 && (
        <>
          <h3 className="mt-16 mb-8">Changes</h3>
          <div className="timeline">
            {d.changes.map(c => (
              <div key={`${c.at}-${c.action}`} className="tl-item">
                <div className="small">
                  <b>{c.action}</b> · {c.actor}
                </div>
                <div className="muted xs">
                  {fmtDateTime(c.at)}
                  {c.after ? ` · ${c.after}` : ''}
                </div>
              </div>
            ))}
          </div>
        </>
      )}
      {person && !can('attendance.edit') && (
        <p className="muted small mt-16">{needs('attendance.edit')} to void or flip this punch.</p>
      )}
      {!person && ev.decision === 'PENDING_REVIEW' && d.review && can('review.resolve') && (
        <ButtonLink className="mt-16" icon="review" iconSize="default" to={`/review?item=${d.review.code}`}>
          Resolve in Review Queue
        </ButtonLink>
      )}
    </>
  );
}

export function EventDrawer({
  code,
  onClose,
  onOpenDay,
}: {
  code: string;
  onClose: () => void;
  onOpenDay: (employeeId: string, day: string) => void;
}) {
  const crossing = useCrossing(code);
  const can = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const correct = useCorrectionFlow();
  const d = crossing.data;
  const notFound = crossing.error instanceof ApiError && crossing.error.status === 404;

  useEffect(() => {
    if (!notFound) return;
    toast(`Event ${code} wasn’t found — it may be older than the retention period`, { type: 'warn' });
    onClose();
  }, [notFound, code, onClose]);

  const ev = d?.event;
  const fix = can('attendance.edit');
  const correction = (kind: 'FLIP' | 'VOID', label: string, icon: 'arrowUpDown' | 'ban') =>
    ev?.person && (
      <Button
        icon={icon}
        iconSize="default"
        disabled={!fix}
        tooltip={fix ? undefined : needs('attendance.edit')}
        onClick={() => {
          if (ev.person) {
            void correct(
              { code: ev.code, direction: ev.direction, at: ev.occurredAt, employeeName: ev.person.name },
              kind,
            );
          }
        }}
      >
        {label}
      </Button>
    );

  return (
    <Drawer
      open
      onClose={onClose}
      title={`Crossing ${code}`}
      sub={ev ? `${fmtDateTime(ev.occurredAt)} · ${ev.direction}` : undefined}
      footer={
        ev?.person && !d?.voided ? (
          <>
            {correction('FLIP', `Flip to ${ev.direction === 'IN' ? 'OUT' : 'IN'}`, 'arrowUpDown')}
            {correction('VOID', 'Void punch', 'ban')}
          </>
        ) : undefined
      }
    >
      {d ? (
        <Body d={d} onOpenDay={onOpenDay} />
      ) : crossing.isError && !notFound ? (
        <p className="muted small">
          <Icon name="alertCircle" size="sm" /> {crossing.error.message}
        </p>
      ) : (
        <SkeletonRows rows={6} columns={2} />
      )}
    </Drawer>
  );
}
