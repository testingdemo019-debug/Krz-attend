/**
 * Step 4a, guided live capture (the prototype's liveCaptureHTML and its loop): "Get ready", the camera with the oval,
 * MediaPipe's on-device checks and hint, auto-capture after a second of passing checks, five poses, then the
 * blink-and-turn challenge. Every capture goes to the server, whose quality and liveness verdict decides: a rejected
 * capture is discarded and the pose asked again; liveness failures count towards the lockout.
 */
import type { FaceLandmarker } from '@mediapipe/tasks-vision';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import {
  useCapture,
  useRemoveCapture,
  useUnlockEnrolment,
  type Candidate,
  type CaptureResult,
  type EnrolmentRules,
  type EnrolmentSession,
} from '@/api/enrolment';
import { EmptyState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { LivePill } from '@/components/ui/LivePill';
import { fmtTime } from '@/lib/format';
import { useCan } from '@/lib/permissions';
import {
  CAPTURE_CHECKS,
  CAPTURE_POSES,
  evaluate,
  newLiveness,
  observe,
  regionStats,
  stepLiveness,
  type Checks,
  type Liveness,
  type Observation,
  type PoseKey,
} from './guidance';
import {
  CAMERA_ERRORS,
  cameraAvailable,
  facePreview,
  frameJpeg,
  openCamera,
  stopCamera,
  videoInputs,
  type CameraError,
} from './camera';
import { PoseIcon, READY_TIPS, TipIcon } from './illustrations';
import { landmarker } from './landmarker';
import { lockedNow, PersonBar } from './SelectStep';
import { WizardNav } from './WizardNav';
import '@/components/domain/CameraTile.module.css';
import '@/components/feedback/EmptyState.module.css';
import '@/components/form/Field.module.css';
import './Enrolment.module.css';

type Phase = 'ready' | 'starting' | 'capturing' | 'liveness' | 'done' | 'error' | 'timeout';

const RING = 2 * Math.PI * 99;
const LANDSCAPE_PHONE = '(orientation: landscape) and (max-height: 520px) and (pointer: coarse)';

export interface LiveCaptureProps {
  candidate: Candidate;
  session: EnrolmentSession;
  rules: EnrolmentRules;
  poses: Partial<Record<PoseKey, number>>;
  images: Record<number, string>;
  guidance: 'mp' | 'manual' | null;
  livenessPassed: boolean;
  onCaptured: (result: CaptureResult, image: string, pose: PoseKey) => void;
  onRemoved: (captureId: number) => void;
  onGuidance: (guidance: 'mp' | 'manual') => void;
  onLiveness: (passed: boolean) => void;
  onUnlocked: () => void;
  onBack: () => void;
  onNext: () => void;
}

/** The first pose without an accepted capture (the number of poses when all have one). */
function nextPose(poses: Partial<Record<PoseKey, number>>): number {
  const i = CAPTURE_POSES.findIndex(p => poses[p.key] === undefined);
  return i < 0 ? CAPTURE_POSES.length : i;
}

export function LiveCapture(props: LiveCaptureProps) {
  const { candidate, session, rules, poses, images, guidance } = props;
  const first = candidate.employee.name.split(' ')[0] ?? candidate.employee.name;
  const lock = lockedNow(session.lockedUntil ?? candidate.lockedUntil);
  const [phase, setPhase] = useState<Phase>('ready');
  const [error, setError] = useState<CameraError>('generic');
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
  const [deviceId, setDeviceId] = useState('');
  const [checks, setChecks] = useState<Checks>({});
  const [hint, setHint] = useState('Starting…');
  const [liveness, setLiveness] = useState<Liveness | null>(null);
  const [failNote, setFailNote] = useState<string | null>(null);
  const [lowFps, setLowFps] = useState(false);
  const [landscape, setLandscape] = useState(false);
  const [timer, setTimer] = useState(90);
  const capture = useCapture();
  const remove = useRemoveCapture();
  const videoRef = useRef<HTMLVideoElement>(null);
  const edgeRef = useRef<SVGEllipseElement>(null);
  const ringRef = useRef<SVGEllipseElement>(null);
  const guideRef = useRef<SVGPathElement>(null);
  const shutterRef = useRef<HTMLDivElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const model = useRef<FaceLandmarker | null>(null);
  const patch = useRef<CanvasRenderingContext2D | null>(null);
  // What the frame loop keeps between frames (the prototype's L).
  const loop = useRef({
    passSince: 0,
    lastCapture: 0,
    pitchBase: null as number | null,
    previous: null as { x: number; y: number } | null,
    lastObs: null as Observation | null,
    busy: false,
    frames: 0,
    fpsT: 0,
    lastVT: -1,
    checksSig: '',
    hint: '',
    liveness: null as Liveness | null,
  });

  const poseIdx = nextPose(poses);
  const good = session.captures.filter(c => c.accepted).length;
  const capturedCount = Object.keys(poses).length;
  const needsChallenge = rules.livenessRequired && guidance === 'mp';

  useEffect(() => {
    void videoInputs().then(setDevices);
    return () => {
      stopCamera(stream.current);
      stream.current = null;
    };
  }, []);

  // The stream goes to the preview once it is on screen.
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !stream.current || video.srcObject === stream.current) return;
    video.srcObject = stream.current;
    void video.play().catch(() => undefined);
  }, [phase]);

  const say = (text: string): void => {
    if (loop.current.hint === text) return;
    loop.current.hint = text;
    setHint(text);
  };

  const stop = (next: Phase): void => {
    stopCamera(stream.current);
    stream.current = null;
    setPhase(next);
  };

  const startLiveness = (): void => {
    const lv = newLiveness(performance.now());
    loop.current.liveness = lv;
    setLiveness(lv);
    setPhase('liveness');
    say('Blink twice');
  };

  const afterPoses = (): void => {
    if (needsChallenge && !props.livenessPassed) startLiveness();
    else setPhase('done');
  };

  const start = async (): Promise<void> => {
    setPhase('starting');
    const opened = await openCamera(deviceId);
    if ('error' in opened) {
      setError(opened.error);
      setPhase('error');
      return;
    }
    stream.current = opened.stream;
    Object.assign(loop.current, { passSince: 0, lastCapture: Date.now(), checksSig: '', hint: '', busy: false });
    setFailNote(null);
    if (poseIdx >= CAPTURE_POSES.length) afterPoses();
    else {
      setPhase('capturing');
      say(guidance === 'manual' ? (CAPTURE_POSES[poseIdx]?.hint ?? '') : 'Starting…');
    }
    if (guidance !== 'manual') {
      landmarker('VIDEO')
        .then(lm => {
          model.current = lm;
          props.onGuidance('mp');
        })
        .catch(() => {
          props.onGuidance('manual');
          toast('Face guidance couldn’t load — use Capture now for each pose; the server checks every image', {
            type: 'warn',
            timeout: 6000,
          });
        });
    }
  };

  const shoot = async (): Promise<void> => {
    const video = videoRef.current;
    const pose = CAPTURE_POSES[nextPose(poses)];
    const state = loop.current;
    if (!video?.videoWidth || !pose || state.busy) return;
    state.busy = true;
    say('Checking…');
    const shutter = shutterRef.current;
    if (shutter) {
      shutter.classList.remove('go');
      // Restart the flash animation.
      shutter.getBoundingClientRect();
      shutter.classList.add('go');
    }
    const preview = facePreview(video, state.lastObs?.box ?? null);
    const frame = await frameJpeg(video);
    try {
      if (!frame) throw new Error('Couldn’t take a picture from the camera');
      const result = await capture.mutateAsync({ code: session.code, image: frame, pose: pose.api });
      state.passSince = 0;
      if (result.capture.accepted) {
        if (pose.key === 'front' && guidance === 'mp' && state.lastObs) state.pitchBase = state.lastObs.pr;
        state.lastCapture = Date.now();
        setFailNote(null);
        props.onCaptured(result, preview, pose.key);
        const left = CAPTURE_POSES.filter(p => p.key !== pose.key && poses[p.key] === undefined);
        if (left.length === 0) afterPoses();
        else say(guidance === 'manual' ? (left[0]?.hint ?? '') : 'Face detected');
        return;
      }
      // A rejected capture never becomes a template: it goes, and the pose is asked again.
      props.onCaptured(result, preview, pose.key);
      remove.mutate(
        { code: session.code, captureId: result.capture.id },
        {
          onSuccess: () => {
            props.onRemoved(result.capture.id);
          },
        },
      );
      if (result.lockedUntil) {
        stop('ready');
      } else if (result.failedAttempts !== null) {
        const attempt = `Attempt ${String(result.failedAttempts)} of ${String(rules.lockFailures)}`;
        setFailNote(attempt);
        toast(`Liveness failed — ${attempt.toLowerCase()}`, { type: 'warn' });
      } else {
        toast(result.capture.rejectReason ?? 'This capture can’t be used — try again', { type: 'warn' });
      }
      say(pose.hint);
    } catch (e) {
      toast(e instanceof Error ? e.message : 'The capture couldn’t be checked', { type: 'error' });
    } finally {
      state.busy = false;
    }
  };

  // One animation frame (the prototype's loop): observe, check, guide, auto-capture, challenge, time out.
  const onFrame = useEffectEvent((now: number) => {
    const state = loop.current;
    const video = videoRef.current;
    const turned = window.matchMedia(LANDSCAPE_PHONE).matches;
    if (turned !== landscape) setLandscape(turned);
    if (phase === 'done' || !video) return;

    let obs: Observation | { n: 0 } | null = null;
    const lm = model.current;
    if (guidance === 'mp' && lm && video.readyState >= 2 && video.currentTime !== state.lastVT) {
      state.lastVT = video.currentTime;
      try {
        const res = lm.detectForVideo(video, now);
        const faces = res.faceLandmarks;
        const box = faces[0]
          ? faces[0].reduce(
              (b, p) => ({
                x0: Math.min(b.x0, p.x),
                y0: Math.min(b.y0, p.y),
                x1: Math.max(b.x1, p.x),
                y1: Math.max(b.y1, p.y),
              }),
              { x0: 1, y0: 1, x1: 0, y1: 0 },
            )
          : null;
        let region = { light: state.lastObs?.light ?? 120, lap: state.lastObs?.lap ?? 0 };
        if (box) {
          patch.current ??= (() => {
            const c = document.createElement('canvas');
            c.width = 64;
            c.height = 64;
            return c.getContext('2d', { willReadFrequently: true });
          })();
          const ctx = patch.current;
          if (ctx) {
            const w = video.videoWidth;
            const h = video.videoHeight;
            ctx.drawImage(video, box.x0 * w, box.y0 * h, (box.x1 - box.x0) * w, (box.y1 - box.y0) * h, 0, 0, 64, 64);
            region = regionStats(ctx.getImageData(0, 0, 64, 64).data);
          }
        }
        obs = observe(
          faces,
          res.faceBlendshapes[0]?.categories ?? [],
          { width: video.videoWidth, height: video.videoHeight },
          region,
          state.previous,
        );
        if ('cx' in obs) state.previous = { x: obs.cx, y: obs.cy };
      } catch {
        obs = null;
      }
      state.frames += 1;
    }
    if (now - state.fpsT > 1000) {
      const fps = (state.frames * 1000) / (now - state.fpsT);
      state.frames = 0;
      state.fpsT = now;
      const low = guidance === 'mp' && fps < 8;
      if (low !== lowFps) setLowFps(low);
    }

    if (phase === 'capturing' && obs && !state.busy) {
      const pose = CAPTURE_POSES[poseIdx];
      if (pose) {
        const r = evaluate(obs, pose, state.pitchBase);
        if (r.all) state.passSince ||= now;
        else state.passSince = 0;
        const progress = r.all ? Math.min(1, Math.max(0, (now - state.passSince) / 1000)) : 0;
        edgeRef.current?.setAttribute('stroke', obs.n === 0 ? '#9aa3af' : r.all ? '#3fbf78' : '#f0a945');
        ringRef.current?.setAttribute('stroke-dasharray', `${String(RING * progress)} ${String(RING)}`);
        say(r.hint ?? (state.passSince ? 'Hold still' : 'Face detected'));
        drawGuide(guideRef.current, pose.key, now);
        const sig = JSON.stringify(r.checks);
        if (sig !== state.checksSig) {
          state.checksSig = sig;
          setChecks(r.checks);
        }
        if ('cx' in obs) state.lastObs = obs;
        if (r.all && progress >= 1) void shoot();
      }
    } else if (phase === 'liveness' && obs && state.liveness) {
      const { next, outcome } = stepLiveness(state.liveness, obs, now);
      if (outcome === 'failed') {
        // On-device only: a missed challenge is asked again; the server's verdicts count towards the lockout.
        const fresh = newLiveness(now);
        state.liveness = fresh;
        setLiveness(fresh);
        setFailNote('');
        say('Blink twice');
        toast('Liveness check not completed — try again', { type: 'warn' });
      } else if (outcome === 'passed') {
        state.liveness = next;
        setLiveness(next);
        setFailNote(null);
        edgeRef.current?.setAttribute('stroke', '#3fbf78');
        say('Liveness passed');
        props.onLiveness(true);
        setPhase('done');
        toast('Liveness passed', { type: 'success', timeout: 1800 });
      } else {
        if (next.current === 'turn' && state.liveness.current === 'blink')
          say('Slowly turn your head left, then right');
        if (JSON.stringify(next) !== JSON.stringify(state.liveness)) setLiveness(next);
        state.liveness = next;
      }
    }

    if (phase === 'capturing') {
      const left = Math.max(0, 90 - Math.round((Date.now() - state.lastCapture) / 1000));
      if (left !== timer) setTimer(left);
      if (left === 0) stop('timeout');
    }
  });

  useEffect(() => {
    if (phase !== 'capturing' && phase !== 'liveness' && phase !== 'done') return;
    let raf = 0;
    loop.current.fpsT = performance.now();
    const tick = (now: number): void => {
      onFrame(now);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
    };
  }, [phase]);

  if (lock) return <Locked candidate={candidate} until={lock} rules={rules} onUnlocked={props.onUnlocked} />;

  if (phase === 'ready' || phase === 'starting') {
    return (
      <>
        <PersonBar candidate={candidate} />
        <h2 className="mb-4">Get ready</h2>
        <p className="muted small mb-16">
          Share these tips with {first} before starting. The camera turns on only after you press Start.
        </p>
        <div className="tips">
          {READY_TIPS.map(([kind, text]) => (
            <div key={kind} className="tip">
              <TipIcon kind={kind} />
              {text}
            </div>
          ))}
        </div>
        <div className="row wrap gap-12 mt-24">
          <Button
            variant="primary"
            size="lg"
            icon="camera"
            iconSize="default"
            pending={phase === 'starting'}
            onClick={() => void start()}
          >
            {phase === 'starting' ? 'Starting camera…' : 'Start camera'}
          </Button>
          <select
            className="select"
            style={{ width: 260 }}
            aria-label="Camera device"
            value={deviceId}
            onChange={event => {
              setDeviceId(event.target.value);
            }}
          >
            <option value="">Default camera</option>
            {devices.map((d, i) => (
              <option key={d.deviceId} value={d.deviceId}>
                {d.label || `Camera ${String(i + 1)}`}
              </option>
            ))}
          </select>
        </div>
        {!cameraAvailable() && (
          <Callout tone="warn" icon="lock" className="mt-16">
            <b>Camera blocked by the browser.</b> This page isn’t in a secure context. Open it from{' '}
            <span className="mono">https://</span> or <span className="mono">http://localhost</span> to use the camera.
          </Callout>
        )}
        <WizardNav
          onBack={props.onBack}
          {...(capturedCount > 0 ? { next: `Review ${String(capturedCount)} captures`, onNext: props.onNext } : {})}
        />
      </>
    );
  }

  if (phase === 'error') {
    const [icon, title, text] = CAMERA_ERRORS[error];
    return (
      <>
        <PersonBar candidate={candidate} />
        <div className="lock-card">
          <EmptyState
            icon={icon}
            title={title}
            text={text}
            action={
              <div className="row gap-8" style={{ justifyContent: 'center' }}>
                <Button
                  icon="refresh"
                  onClick={() => {
                    setPhase('ready');
                  }}
                >
                  Try again
                </Button>
              </div>
            }
          />
        </div>
      </>
    );
  }

  if (phase === 'timeout') {
    return (
      <>
        <PersonBar candidate={candidate} />
        <div className="lock-card">
          <EmptyState
            icon="hourglass"
            title="Let’s try again"
            text="No good capture in 90 seconds. Check the tips: face the light, move a little closer, keep still and look at the camera."
            action={
              <>
                <div className="tips mb-16" style={{ textAlign: 'left' }}>
                  {READY_TIPS.filter(([k]) => k === 'light' || k === 'one').map(([kind]) => (
                    <div key={kind} className="tip">
                      <TipIcon kind={kind} />
                      {kind === 'light' ? 'Face the light' : 'One person only'}
                    </div>
                  ))}
                </div>
                <Button
                  variant="primary"
                  icon="refresh"
                  onClick={() => {
                    setPhase('ready');
                  }}
                >
                  Try again
                </Button>
              </>
            }
          />
        </div>
      </>
    );
  }

  const pose = CAPTURE_POSES[poseIdx] ?? CAPTURE_POSES[0];
  const challenge = phase === 'liveness' || (phase === 'done' && props.livenessPassed);
  return (
    <>
      <PersonBar candidate={candidate} />
      <div className="capture">
        <div>
          <div className="preview">
            <video ref={videoRef} className="mirror" playsInline muted />
            <svg className="ovl" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
              <defs>
                <mask id="ovm">
                  <rect width="400" height="300" fill="white" />
                  <ellipse cx="200" cy="138" rx="78" ry="104" fill="black" />
                </mask>
              </defs>
              <rect width="400" height="300" fill="rgba(5,7,10,.55)" mask="url(#ovm)" />
              <ellipse ref={edgeRef} cx="200" cy="138" rx="78" ry="104" fill="none" stroke="#9aa3af" strokeWidth="3" />
              <ellipse
                ref={ringRef}
                cx="200"
                cy="138"
                rx="86"
                ry="112"
                fill="none"
                stroke="#3fbf78"
                strokeWidth="5"
                strokeLinecap="round"
                strokeDasharray="0 1000"
                transform="rotate(-90 200 138)"
              />
              <path ref={guideRef} opacity="0" fill="#ffffff" />
            </svg>
            <div className="pmode">
              {guidance === 'mp' ? (
                <LivePill state="live" text="Live guidance" />
              ) : (
                <LivePill state="conn" text={guidance === 'manual' ? 'Camera · manual capture' : 'Camera'} />
              )}
              {lowFps && <LivePill state="rec" text="Low frame rate" />}
            </div>
            <div className="hint" role="status" aria-live="polite">
              {hint}
            </div>
            <div ref={shutterRef} className="shutter" />
            {landscape && (
              <div className="tile-banner">
                <Icon name="rotate" size="lg" />
                <b>Rotate your device</b>
                <span className="xs">Hold the tablet or phone upright (portrait) for enrolment.</span>
              </div>
            )}
          </div>
          <div className="row between mt-12 wrap gap-8">
            <div className="poses">
              {CAPTURE_POSES.map((p, i) => {
                const id = poses[p.key];
                const src = id === undefined ? undefined : images[id];
                return (
                  <div
                    key={p.key}
                    className={`pose ${id !== undefined ? 'done' : i === poseIdx && phase === 'capturing' ? 'now' : ''}`}
                  >
                    {src ? (
                      <img
                        src={src}
                        alt=""
                        style={{ width: 28, height: 28, borderRadius: '50%', objectFit: 'cover' }}
                      />
                    ) : (
                      <PoseIcon kind={p.key} />
                    )}
                    <span>
                      {id !== undefined && <Icon name="check" size="sm" />}
                      {p.label}
                    </span>
                  </div>
                );
              })}
              <div className={`pose ${phase === 'liveness' ? 'now' : phase === 'done' ? 'done' : ''}`}>
                <Icon name="fingerprint" />
                <span>Liveness</span>
              </div>
            </div>
            <div className="row gap-8">
              {phase === 'capturing' && (
                <Button
                  icon="camera"
                  onClick={() => {
                    if (guidance === 'mp' && !(checks.face?.ok && checks.one?.ok)) {
                      toast('No single face detected yet', { type: 'warn' });
                      return;
                    }
                    void shoot();
                  }}
                >
                  Capture now
                </Button>
              )}
              <Button
                icon="x"
                onClick={() => {
                  stop('ready');
                }}
              >
                Stop
              </Button>
            </div>
          </div>
        </div>
        <div>
          <h3 className="mb-8">
            {challenge
              ? 'Liveness check'
              : phase === 'done'
                ? 'All poses captured'
                : `Pose ${String(poseIdx + 1)} of ${String(CAPTURE_POSES.length)}: ${pose?.label ?? ''}`}
          </h3>
          {failNote !== null && (
            <Callout tone="danger" icon="shieldAlert" className="mb-12">
              <b>Liveness check failed.</b> Let’s try again — follow the prompt slowly.
              {failNote ? ` ${failNote}.` : ''}
            </Callout>
          )}
          {challenge ? (
            <LivenessPanel
              liveness={liveness}
              failures={session.livenessFailures}
              rules={rules}
              passed={phase === 'done'}
            />
          ) : phase === 'done' ? null : (
            <div className="checklist">
              {CAPTURE_CHECKS.map(([key, label]) => {
                const c = checks[key];
                const status = c === undefined ? '' : c.ok ? 'pass' : 'fail';
                return (
                  <div key={key} className={`ck ${status}`}>
                    <span className="ck-ic">
                      <Icon name={c?.ok ? 'check' : c ? 'alert' : 'dot'} size="sm" />
                    </span>
                    <span className="grow">
                      {key === 'pose' ? pose?.check : label}
                      {c && !c.ok && c.hint && (
                        <>
                          <br />
                          <span className="ck-sub">{c.hint}</span>
                        </>
                      )}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
          <p className="muted xs mt-12">
            {guidance === 'manual'
              ? 'Face guidance isn’t available on this device: use Capture now for each pose. The server checks every capture.'
              : 'Checks run on this device using MediaPipe Face Landmarker; the server checks every capture again. Auto-captures when every check passes for 1 second.'}
          </p>
          {phase === 'capturing' && <div className="muted xs mt-4">Auto-retry tips in {timer} s if no capture</div>}
        </div>
      </div>
      <WizardNav
        onBack={() => {
          stop('ready');
          props.onBack();
        }}
        next="Review captures"
        disabled={good < rules.minGood || !(phase === 'done' || !rules.livenessRequired)}
        onNext={() => {
          stop('ready');
          props.onNext();
        }}
      />
    </>
  );
}

/** The prototype's drawPoseGuide: an arrow pulsing towards the turn or under the chin. */
function drawGuide(arrow: SVGPathElement | null, pose: PoseKey, now: number): void {
  if (!arrow) return;
  const dir = pose === 'left' ? -1 : pose === 'right' ? 1 : 0;
  if (!dir && pose !== 'down') {
    arrow.setAttribute('opacity', '0');
    return;
  }
  const t = (now / 900) % 1;
  arrow.setAttribute('opacity', String(0.9 - t * 0.5));
  if (dir) {
    const x = 200 + dir * (100 + t * 30);
    arrow.setAttribute(
      'd',
      `M${String(x)} 138 l${String(-dir * 18)} -14 v8 h${String(-dir * 26)} v12 h${String(dir * 26)} v8 z`,
    );
  } else {
    arrow.setAttribute('d', `M200 ${String(138 + 112 + t * 14 + 16)} l-14 -18 h8 v-16 h12 v16 h8 z`);
  }
}

function LivenessPanel({
  liveness: lv,
  failures,
  rules,
  passed,
}: {
  liveness: Liveness | null;
  failures: number;
  rules: EnrolmentRules;
  passed: boolean;
}) {
  const row = (key: 'blink' | 'turn', title: string, done: boolean, progress: string) => (
    <div className={`ck ${done ? 'pass' : lv?.current === key ? 'fail' : ''}`}>
      <span className="ck-ic">
        <Icon name={done ? 'check' : 'dot'} size="sm" />
      </span>
      <span className="grow">
        <b>{title}</b>
        {lv?.current === key && (
          <>
            <br />
            <span className="ck-sub" style={{ color: 'var(--text-2)' }}>
              {progress}
            </span>
          </>
        )}
      </span>
    </div>
  );
  return (
    <>
      <div className="checklist">
        {row('blink', 'Blink twice', lv?.blinkDone ?? passed, `${String(lv?.blinks ?? 0)} of 2 blinks`)}
        {row(
          'turn',
          'Slowly turn your head left, then right',
          lv?.turnDone ?? passed,
          `${lv?.turnedLeft ? 'Left ✓' : 'Left …'} · ${lv?.turnedRight ? 'Right ✓' : 'Right …'}`,
        )}
      </div>
      <p className="small mt-12">
        Result is shown as pass or fail only. Attempts in the last {rules.lockWindowMin} min: <b>{failures}</b> of{' '}
        {rules.lockFailures}.
      </p>
      {passed && (
        <Callout tone="ok" icon="shieldCheck" className="mt-12">
          <b>Liveness passed.</b> Continue to review the captures.
        </Callout>
      )}
    </>
  );
}

/** The prototype's lockedHTML, with the System Administrator's unlock. */
function Locked({
  candidate,
  until,
  rules,
  onUnlocked,
}: {
  candidate: Candidate;
  until: string;
  rules: EnrolmentRules;
  onUnlocked: () => void;
}) {
  const canUnlock = useCan('settings.roles');
  const unlock = useUnlockEnrolment();
  const first = candidate.employee.name.split(' ')[0] ?? candidate.employee.name;
  return (
    <>
      <PersonBar candidate={candidate} />
      <div className="lock-card">
        <EmptyState
          icon="lock"
          title="Enrolment locked"
          text={
            <>
              {first} failed the liveness check {rules.lockFailures} times in {rules.lockWindowMin} minutes. Enrolment
              is locked until <b>{fmtTime(until)}</b> ({rules.lockMinutes} minutes). If this is a genuine person, a
              System Administrator can unlock it.
            </>
          }
          action={
            <div className="row gap-8" style={{ justifyContent: 'center' }}>
              <ButtonLink to="/employees">Back to employees</ButtonLink>
              {canUnlock && (
                <Button
                  variant="primary"
                  icon="unlock"
                  pending={unlock.isPending}
                  onClick={() => {
                    unlock.mutate(candidate.employee.id, {
                      onSuccess: () => {
                        toast('Enrolment unlocked', { type: 'success', logged: true });
                        onUnlocked();
                      },
                      onError: error => toast(error.message, { type: 'error' }),
                    });
                  }}
                >
                  Unlock now (admin)
                </Button>
              )}
            </div>
          }
        />
      </div>
    </>
  );
}
