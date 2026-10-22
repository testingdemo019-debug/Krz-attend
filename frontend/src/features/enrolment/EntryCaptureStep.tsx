/**
 * Step 4c, capture at an entry point (D-118): the operator chooses the camera and starts; the person walks through it
 * normally a few times and once looking at the camera. The attendance camera's four angle groups fill as it sees good
 * faces of the one person in view; stopping turns them into captures with their angle, quality and light. Their walks
 * are recorded but not counted.
 */
import { useEffect, useRef, useState } from 'react';
import {
  cameraSnapshotUrl,
  captureImageUrl,
  useCaptureCameras,
  useCaptureStatus,
  useRemoveCapture,
  useStartCapture,
  useStopCapture,
  type Candidate,
  type CaptureStatus,
  type EnrolmentRules,
  type EnrolmentSession,
} from '@/api/enrolment';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { plural } from '@/lib/text';
import { TEMPLATE_LIGHTS, TEMPLATE_POSES, term } from '@/lib/vocabulary';
import { PersonBar } from './SelectStep';
import { WizardNav } from './WizardNav';
import './Enrolment.module.css';

/** The snapshot of the camera refreshes this often (ms). */
const SNAPSHOT_EVERY = 1000;

/** "Turn left · Day" for a capture of the entry camera. */
export function entryCaptureLabel(pose: string | null | undefined, light: string | null | undefined): string {
  const angle = pose ? (term(TEMPLATE_POSES, pose) ?? pose) : 'Face';
  return light ? `${angle} · ${term(TEMPLATE_LIGHTS, light) ?? light}` : angle;
}

export function EntryCaptureStep({
  candidate,
  session,
  rules,
  onSession,
  onBack,
  onNext,
}: {
  candidate: Candidate;
  session: EnrolmentSession;
  rules: EnrolmentRules;
  onSession: (session: EnrolmentSession) => void;
  onBack: () => void;
  onNext: () => void;
}) {
  const cameras = useCaptureCameras(true);
  const start = useStartCapture();
  const stop = useStopCapture();
  const remove = useRemoveCapture();
  const [camera, setCamera] = useState<string | null>(session.cameraCode ?? null);
  const [running, setRunning] = useState(false);
  const [snapshotFailed, setSnapshotFailed] = useState(false);
  const [tick, setTick] = useState(0);
  const status = useCaptureStatus(session.code, running);
  const stopping = useRef(false);
  const first = candidate.employee.name.split(' ')[0] ?? candidate.employee.name;
  const list = cameras.data ?? [];
  const chosen = list.find(c => c.code === camera) ?? list[0] ?? null;
  const live: CaptureStatus | undefined = running ? (status.data ?? start.data) : undefined;
  const captures = session.captures;
  const good = captures.filter(c => c.accepted).length;
  const needed = candidate.templates.length >= rules.minGood ? 1 : rules.minGood;

  useEffect(() => {
    if (!chosen) return undefined;
    const timer = window.setInterval(() => {
      setTick(t => t + 1);
    }, SNAPSHOT_EVERY);
    return () => {
      window.clearInterval(timer);
    };
  }, [chosen]);

  const finish = (): void => {
    if (stopping.current) return;
    stopping.current = true;
    stop.mutate(session.code, {
      onSuccess: next => {
        onSession(next);
        const kept = next.captures.length - captures.length;
        toast(kept > 0 ? `${plural(kept, 'picture')} from the entry camera` : 'No good face was seen — try again', {
          type: kept > 0 ? 'success' : 'warn',
        });
      },
      onError: error => toast(error.message, { type: 'error' }),
      onSettled: () => {
        stopping.current = false;
        setRunning(false);
      },
    });
  };

  // Time is up at the worker: collect what it holds.
  useEffect(() => {
    if (running && status.data && status.data.status !== 'RUNNING') finish();
  });

  const begin = (): void => {
    if (!chosen) return;
    start.mutate(
      { code: session.code, cameraCode: chosen.code },
      {
        onSuccess: () => {
          setRunning(true);
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };

  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-4">Capture at entry point</h2>
      <p className="muted small mb-12">
        {first} walks through {chosen?.entryPoint ?? 'the entry point'} normally two or three times, and once looking at
        the camera. Only {first} should be in view. These walks are recorded but not counted for attendance.
      </p>
      <div className="grid grid-2">
        <div className="card pad">
          <div className="row between wrap gap-8 mb-8">
            {list.length > 1 ? (
              <label className="field" style={{ minWidth: 0 }}>
                <span className="label">Camera</span>
                <select
                  className="select"
                  value={chosen?.code ?? ''}
                  disabled={running}
                  onChange={event => {
                    setCamera(event.target.value);
                    setSnapshotFailed(false);
                  }}
                >
                  {list.map(c => (
                    <option key={c.code} value={c.code}>
                      {c.name}
                      {c.entryPoint ? ` · ${c.entryPoint}` : ''}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <b>{chosen ? `${chosen.name}${chosen.entryPoint ? ` · ${chosen.entryPoint}` : ''}` : 'No camera'}</b>
            )}
            {running ? (
              <Button icon="pause" onClick={finish} disabled={stop.isPending}>
                Stop capture{live ? ` · ${String(live.secondsLeft)} s` : ''}
              </Button>
            ) : (
              <Button variant="primary" icon="play" onClick={begin} disabled={!chosen || start.isPending}>
                Start capture
              </Button>
            )}
          </div>
          {chosen && !snapshotFailed ? (
            <img
              src={cameraSnapshotUrl(chosen.code, tick)}
              alt={`Live view of ${chosen.name}`}
              style={{ width: '100%', borderRadius: 8, display: 'block', background: 'var(--surface-2)' }}
              onError={() => {
                setSnapshotFailed(true);
              }}
            />
          ) : (
            <div className="muted small">
              <Icon name="wifiOff" size="sm" /> No picture from this camera right now.
            </div>
          )}
        </div>
        <div className="col gap-12" style={{ minWidth: 0 }}>
          {live?.crowded && (
            <Callout tone="warn" icon="users" className="small">
              More than one person is in view. No pictures are taken until only {first} is in view.
            </Callout>
          )}
          {live && live.otherPerson > 0 && (
            <Callout tone="neutral" icon="info" className="small">
              {plural(live.otherPerson, 'picture')} looked like someone else and{' '}
              {live.otherPerson === 1 ? 'was' : 'were'} skipped.
            </Callout>
          )}
          <div className="grid grid-2">
            {Object.entries(TEMPLATE_POSES).map(([key, label]) => {
              const samples = live?.groups.find(g => g.group === key)?.samples ?? [];
              const best = samples[0];
              return (
                <div key={key} className="card pad">
                  <div className="row between gap-8">
                    <b>{label}</b>
                    {best ? (
                      <Chip tone="green" icon="check">
                        Q {best.score ?? '—'}
                      </Chip>
                    ) : (
                      <Chip tone="grey">{running ? 'Waiting…' : 'Not yet'}</Chip>
                    )}
                  </div>
                  <div className="muted xs mt-4">
                    {best
                      ? `${String(best.eyePx ?? '—')} px · ${term(TEMPLATE_LIGHTS, best.light ?? '') ?? ''}`
                      : running
                        ? 'Walk through again'
                        : '—'}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
      {captures.length > 0 && (
        <div className="cap-grid mt-16">
          {captures.map(c => {
            const label = entryCaptureLabel(c.pose, c.light);
            const reasons = c.rejectReason ? c.rejectReason.split('; ') : [];
            return (
              <div key={c.id} className={`cap ${c.accepted ? '' : 'bad'}`}>
                <div className="img">
                  <img src={captureImageUrl(session.code, c.id)} alt={`Face crop · ${label}`} />
                  <Chip tone={c.accepted ? 'green' : 'red'} icon={c.accepted ? 'check' : 'x'} className="qs">
                    {c.accepted ? 'Pass' : 'Fail'}
                  </Chip>
                </div>
                <div className="meta">
                  <b className="ellipsis" title={label}>
                    {label}
                  </b>
                  {c.accepted ? (
                    <span className="muted">
                      Q {c.quality?.score ?? '—'} · {c.quality?.eyePx ?? '—'} px · sharp {c.quality?.sharp ?? '—'}
                    </span>
                  ) : (
                    reasons.map(r => (
                      <span key={r} style={{ color: 'var(--red)' }}>
                        <Icon name="alertCircle" size="sm" /> {r}
                      </span>
                    ))
                  )}
                  <Button
                    size="sm"
                    icon="trash"
                    className="mt-4"
                    onClick={() => {
                      remove.mutate(
                        { code: session.code, captureId: c.id },
                        {
                          onSuccess: onSession,
                          onError: error => toast(error.message, { type: 'error' }),
                        },
                      );
                    }}
                  >
                    Remove
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      )}
      <WizardNav
        onBack={onBack}
        next={`Review ${plural(good, 'good image')}`}
        disabled={running || good < needed}
        onNext={onNext}
      />
    </>
  );
}
