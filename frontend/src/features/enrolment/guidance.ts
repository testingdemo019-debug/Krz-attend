/**
 * The prototype's on-device capture guidance (observeMP, poseOk, evaluateChecks, evaluateLiveness), as pure
 * functions: what MediaPipe's landmarks say about the face, which checks pass, the hint to show and the blink-and-turn
 * challenge. It only guides the person; the server's quality and liveness verdict on each capture is what counts.
 */

export const CAPTURE_CHECKS = [
  ['face', 'Face detected'],
  ['one', 'Only one face'],
  ['center', 'Face centred in the oval'],
  ['dist', 'Distance OK'],
  ['pose', 'Looking straight / pose within limits'],
  ['light', 'Lighting OK'],
  ['sharp', 'Sharp image (hold still)'],
  ['eyes', 'Eyes open, face not covered'],
] as const;
export type CheckKey = (typeof CAPTURE_CHECKS)[number][0];

export type PoseKey = 'front' | 'front2' | 'left' | 'right' | 'down';
export interface CapturePose {
  key: PoseKey;
  label: string;
  hint: string;
  check: string;
  /** The pose the server stores with the template. */
  api: 'FRONT' | 'TURN_LEFT' | 'TURN_RIGHT' | 'CHIN_DOWN';
}

export const CAPTURE_POSES: readonly CapturePose[] = [
  { key: 'front', label: 'Front', hint: 'Look straight at the camera', check: 'Looking straight', api: 'FRONT' },
  {
    key: 'front2',
    label: 'Front (second)',
    hint: 'Look straight at the camera',
    check: 'Looking straight',
    api: 'FRONT',
  },
  {
    key: 'left',
    label: 'Turn slightly left',
    hint: 'Turn your head slightly left',
    check: 'Head turned slightly left',
    api: 'TURN_LEFT',
  },
  {
    key: 'right',
    label: 'Turn slightly right',
    hint: 'Turn your head slightly right',
    check: 'Head turned slightly right',
    api: 'TURN_RIGHT',
  },
  {
    key: 'down',
    label: 'Chin slightly down',
    hint: 'Lower your chin slightly',
    check: 'Chin slightly down',
    api: 'CHIN_DOWN',
  },
];

export interface Box {
  x0: number;
  x1: number;
  y0: number;
  y1: number;
}

/** What one video frame shows: faces, where and how big, head turn, eye openness, light, sharpness and motion. */
export interface Observation {
  n: number;
  /** Face centre, mirrored to match the preview (0–1). */
  cx: number;
  cy: number;
  /** Face height as a share of the frame. */
  h: number;
  yaw: number;
  /** Nose position between the eyes and the chin (the pitch measure). */
  pr: number;
  eyePx: number;
  blink: number;
  light: number;
  /** Laplacian variance of the face region (sharpness). */
  lap: number;
  motion: number;
  /** Face box in the mirrored preview's coordinates. */
  box: Box;
  /** Face box in the camera image's coordinates (for cropping). */
  imageBox: Box;
}

export interface Landmark {
  x: number;
  y: number;
}

const DEFAULT_LANDMARK: Landmark = { x: 0.5, y: 0.5 };

/** The prototype's observeMP for the first face; light and sharpness come from the caller's 64×64 patch. */
export function observe(
  faces: readonly (readonly Landmark[])[],
  blendshapes: readonly { categoryName: string; score: number }[],
  video: { width: number; height: number },
  region: { light: number; lap: number },
  previous: { x: number; y: number } | null,
): Observation | { n: 0 } {
  const lm = faces[0];
  if (!lm) return { n: 0 };
  let x0 = 1;
  let y0 = 1;
  let x1 = 0;
  let y1 = 0;
  for (const p of lm) {
    x0 = Math.min(x0, p.x);
    y0 = Math.min(y0, p.y);
    x1 = Math.max(x1, p.x);
    y1 = Math.max(y1, p.y);
  }
  const eL = lm[33] ?? DEFAULT_LANDMARK;
  const eR = lm[263] ?? DEFAULT_LANDMARK;
  const nose = lm[1] ?? DEFAULT_LANDMARK;
  const chin = lm[152] ?? DEFAULT_LANDMARK;
  const mid = { x: (eL.x + eR.x) / 2, y: (eL.y + eR.y) / 2 };
  const span = eR.x - eL.x || 1e-3;
  const score = (name: string) => blendshapes.find(c => c.categoryName === name)?.score ?? 0;
  const cx = 1 - (x0 + x1) / 2;
  const cy = (y0 + y1) / 2;
  return {
    n: faces.length,
    cx,
    cy,
    h: y1 - y0,
    yaw: ((nose.x - mid.x) / span) * 100,
    pr: (nose.y - mid.y) / (chin.y - mid.y || 1e-3),
    eyePx: Math.hypot((eR.x - eL.x) * video.width, (eR.y - eL.y) * video.height) * 0.92,
    blink: Math.max(score('eyeBlinkLeft'), score('eyeBlinkRight')),
    light: region.light,
    lap: region.lap,
    motion: previous ? Math.hypot(cx - previous.x, cy - previous.y) : 0,
    box: { x0: 1 - x1, x1: 1 - x0, y0, y1 },
    imageBox: { x0, x1, y0, y1 },
  };
}

/** Mean brightness and Laplacian variance of a 64×64 grey patch (RGBA pixels), as the prototype measures them. */
export function regionStats(rgba: Uint8ClampedArray): { light: number; lap: number } {
  const g = new Float32Array(4096);
  let sum = 0;
  for (let i = 0; i < 4096; i++) {
    const y = 0.299 * (rgba[i * 4] ?? 0) + 0.587 * (rgba[i * 4 + 1] ?? 0) + 0.114 * (rgba[i * 4 + 2] ?? 0);
    g[i] = y;
    sum += y;
  }
  let m = 0;
  let m2 = 0;
  let c = 0;
  for (let yy = 1; yy < 63; yy++) {
    for (let xx = 1; xx < 63; xx++) {
      const i = yy * 64 + xx;
      const l = 4 * (g[i] ?? 0) - (g[i - 1] ?? 0) - (g[i + 1] ?? 0) - (g[i - 64] ?? 0) - (g[i + 64] ?? 0);
      m += l;
      m2 += l * l;
      c++;
    }
  }
  return { light: sum / 4096, lap: m2 / c - (m / c) ** 2 };
}

/** The prototype's poseOk: null when the head is as the pose asks, otherwise what to do. */
export function poseHint(o: Observation, pose: CapturePose, pitchBase: number | null): string | null {
  const pitch = (o.pr - (pitchBase ?? 0.44)) * 160;
  if (pose.key === 'left') {
    if (o.yaw >= 12 && o.yaw <= 40) return null;
    return o.yaw < 12 ? 'Turn your head slightly left' : 'Not so far — turn back a little';
  }
  if (pose.key === 'right') {
    if (o.yaw <= -12 && o.yaw >= -40) return null;
    return o.yaw > -12 ? 'Turn your head slightly right' : 'Not so far — turn back a little';
  }
  if (pose.key === 'down') {
    if (pitch >= 8 && pitch <= 30 && Math.abs(o.yaw) < 14) return null;
    return pitch < 8 ? 'Lower your chin slightly' : 'Not so far — raise your chin a little';
  }
  return Math.abs(o.yaw) <= 10 && Math.abs(pitch) <= 12 ? null : 'Look straight at the camera';
}

export interface Check {
  ok: boolean;
  hint?: string | null;
}
export type Checks = Partial<Record<CheckKey, Check>>;

const ORDER: readonly CheckKey[] = ['one', 'face', 'center', 'dist', 'pose', 'light', 'sharp', 'eyes'];

/**
 * The prototype's evaluateChecks: every check for this frame, whether all pass and the hint of the first that
 * doesn't (null when all pass).
 */
export function evaluate(
  o: Observation | { n: 0 },
  pose: CapturePose,
  pitchBase: number | null,
): { checks: Checks; all: boolean; hint: string | null } {
  const c: Checks = {
    face: { ok: o.n >= 1, hint: 'Center your face' },
    one: { ok: o.n === 1, hint: 'Ensure only one face is in view' },
  };
  if ('cx' in o) {
    c.center = { ok: Math.abs(o.cx - 0.5) < 0.09 && Math.abs(o.cy - 0.46) < 0.12, hint: 'Center your face' };
    c.dist =
      o.h < 0.3 ? { ok: false, hint: 'Move closer' } : o.h > 0.66 ? { ok: false, hint: 'Move back' } : { ok: true };
    const ph = poseHint(o, pose, pitchBase);
    c.pose = { ok: !ph, hint: ph };
    c.light =
      o.light < 70
        ? { ok: false, hint: 'Move to a brighter area' }
        : o.light > 205
          ? { ok: false, hint: 'Move to a dimmer area' }
          : { ok: true };
    c.sharp = { ok: o.motion < 0.012 && o.lap > 16, hint: 'Hold still' };
    c.eyes = { ok: o.blink < 0.45, hint: 'Open your eyes and uncover your face' };
  }
  const firstBad = ORDER.find(k => !c[k]?.ok);
  return {
    checks: c,
    all: firstBad === undefined,
    hint: firstBad === undefined ? null : (c[firstBad]?.hint ?? 'Center your face'),
  };
}

/** The blink-twice-then-turn challenge after the poses. */
export interface Liveness {
  current: 'blink' | 'turn' | null;
  blinks: number;
  blinkDone: boolean;
  turnedLeft: boolean;
  turnedRight: boolean;
  turnDone: boolean;
  t0: number;
  tTurn: number;
  eyes: 'open' | 'closed';
}

export function newLiveness(now: number): Liveness {
  return {
    current: 'blink',
    blinks: 0,
    blinkDone: false,
    turnedLeft: false,
    turnedRight: false,
    turnDone: false,
    t0: now,
    tTurn: 0,
    eyes: 'open',
  };
}

/** The prototype's evaluateLiveness for one frame: the next state, and whether it passed or ran out of time. */
export function stepLiveness(
  lv: Liveness,
  o: Observation | { n: 0 },
  now: number,
): { next: Liveness; outcome: 'continue' | 'passed' | 'failed' } {
  const next = { ...lv };
  if (next.current === 'blink') {
    if ('blink' in o) {
      if (o.blink > 0.55 && next.eyes === 'open') next.eyes = 'closed';
      else if (o.blink < 0.25 && next.eyes === 'closed') {
        next.eyes = 'open';
        next.blinks += 1;
      }
    }
    if (next.blinks >= 2) {
      next.blinkDone = true;
      next.current = 'turn';
      next.tTurn = now;
    } else if (now - next.t0 > 12_000) return { next, outcome: 'failed' };
  } else if (next.current === 'turn') {
    if ('yaw' in o) {
      if (o.yaw > 15) next.turnedLeft = true;
      if (next.turnedLeft && o.yaw < -15) next.turnedRight = true;
    }
    if (next.turnedLeft && next.turnedRight) {
      next.turnDone = true;
      next.current = null;
      return { next, outcome: 'passed' };
    }
    if (now - next.tTurn > 12_000) return { next, outcome: 'failed' };
  }
  return { next, outcome: 'continue' };
}
