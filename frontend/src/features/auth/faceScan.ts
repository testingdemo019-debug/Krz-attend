/**
 * The sign-in scan's drawing (D-122): MediaPipe's generic face (faceMesh.ts) as a cloud of glowing dots that gathers,
 * turns while a scan line passes and its landmarks light up, scatters, gathers again into a check in a ring and
 * scatters to start over. Canvas 2D in a 360 × 300 drawing, one loop of LOOP_MS; `phaseAt` tells the page which
 * caption belongs to a moment of the loop.
 */
import { FACE_TRIANGLES, FACE_VERTICES } from './faceMesh';

export const LOOP_MS = 7800;
/** The still frame for reduced motion: the face turned to the camera with every landmark lit. */
export const STILL_MS = 2600;

export type ScanPhase = 'scan' | 'verified';

/** When each part of the loop ends, in ms: gather into the face, scan it, scatter, gather into the check, hold. */
const GATHER = 900;
const SCAN = 3500;
const BURST = 3900;
const RING = 4600;
const HOLD = 7000;
/** The caption names the person from just before the check forms until the dots have left it. */
const VERIFIED_FROM = 4200;
const VERIFIED_TO = 7400;
/** The scan line's pass, and the landmarks lighting one after another meanwhile. */
const BEAM_FROM = 1000;
const BEAM_TO = 2500;
const MARKS_FROM = 1100;
const MARKS_TO = 2400;

const W = 360;
const H = 300;
const CX = 180;
const CY = 134;
/** Pixels per millimetre of face, and the camera's distance for the perspective. */
const SCALE = 1.05;
const FOCAL = 600;
/** Dots sampled on the face's surface besides its 468 vertices. */
const SURFACE_DOTS = 900;

const FACE = [120, 190, 255] as const;
const DONE = [95, 211, 148] as const;
const WHITE = [255, 255, 255] as const;
const ORANGE = 'rgb(251, 176, 59)';
/** Dots are drawn in this many brightness steps, so a frame fills a handful of paths instead of one per dot. */
const STEPS = 8;

/** Brows, eye corners, nose tip, mouth corners, lips and chin, by landmark number. */
const MARKS = [70, 105, 334, 300, 33, 133, 362, 263, 1, 61, 291, 13, 17, 152] as const;

interface Point {
  x: number;
  y: number;
  z: number;
}

type Triangle = readonly [Point, Point, Point];

interface Dot extends Point {
  /** Where it waits off the face; how it flies out when the face scatters (turn and distance); its place in the check. */
  sx: number;
  sy: number;
  dir: number;
  far: number;
  cx: number;
  cy: number;
  ring: boolean;
  /** 0–1: how late it moves in a staggered move. */
  lag: number;
}

const clamp = (v: number) => Math.min(1, Math.max(0, v));
const span = (t: number, from: number, to: number) => clamp((t - from) / (to - from));
const easeOut = (u: number) => 1 - (1 - u) ** 3;
const easeIn = (u: number) => u ** 3;
const easeInOut = (u: number) => (u < 0.5 ? 4 * u ** 3 : 1 - (-2 * u + 2) ** 3 / 2);
/** A staggered move: each dot starts within the first 40 % of the move and takes the rest. */
const stagger = (u: number, lag: number) => clamp((u - lag * 0.4) / 0.6);

/** A seeded random number generator (mulberry32): the same dots on every load. */
function random(seed: number) {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const vertex = (i: number): Point => ({
  x: FACE_VERTICES[i * 3] ?? 0,
  y: FACE_VERTICES[i * 3 + 1] ?? 0,
  z: FACE_VERTICES[i * 3 + 2] ?? 0,
});

const VERTICES: readonly Point[] = Array.from({ length: FACE_VERTICES.length / 3 }, (_, i) => vertex(i));

const TRIANGLES: readonly Triangle[] = Array.from({ length: FACE_TRIANGLES.length / 3 }, (_, i) => {
  const [a = 0, b = 0, c = 0] = FACE_TRIANGLES.slice(i * 3, i * 3 + 3);
  return [vertex(a), vertex(b), vertex(c)];
});

/** Each edge of the mesh once, as two vertex numbers. */
const EDGES: readonly (readonly [number, number])[] = (() => {
  const keys = new Set<number>();
  for (let i = 0; i < FACE_TRIANGLES.length; i += 3) {
    const [a = 0, b = 0, c = 0] = FACE_TRIANGLES.slice(i, i + 3);
    for (const [p, q] of [
      [a, b],
      [b, c],
      [c, a],
    ] as const) {
      keys.add(Math.min(p, q) * 1000 + Math.max(p, q));
    }
  }
  return [...keys].map(k => [Math.floor(k / 1000), k % 1000] as const);
})();

const area = ([a, b, c]: Triangle) => {
  const [ux, uy, uz] = [b.x - a.x, b.y - a.y, b.z - a.z];
  const [vx, vy, vz] = [c.x - a.x, c.y - a.y, c.z - a.z];
  return Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx) / 2;
};

/** A point on the face's surface, larger triangles picked more often. */
function onSurface(rand: () => number, weighted: readonly { tri: Triangle; area: number }[], total: number): Point {
  let pick = rand() * total;
  let [a, b, c] = TRIANGLES[0] ?? [vertex(0), vertex(0), vertex(0)];
  for (const w of weighted) {
    [a, b, c] = w.tri;
    pick -= w.area;
    if (pick < 0) break;
  }
  const r1 = Math.sqrt(rand());
  const r2 = rand();
  const [wa, wb, wc] = [1 - r1, r1 * (1 - r2), r1 * r2];
  return { x: wa * a.x + wb * b.x + wc * c.x, y: wa * a.y + wb * b.y + wc * c.y, z: wa * a.z + wb * b.z + wc * c.z };
}

/** A place in the check: on a circle of radius 70, or along the tick inside it. */
function inCheck(rand: () => number, ring: boolean): [number, number] {
  if (ring) {
    const a = rand() * Math.PI * 2;
    const r = 70 + (rand() - 0.5) * 6;
    return [CX + Math.cos(a) * r, CY + Math.sin(a) * r];
  }
  const [x1, y1, x2, y2, x3, y3] = [CX - 33, CY + 2, CX - 10, CY + 25, CX + 35, CY - 24];
  const first = Math.hypot(x2 - x1, y2 - y1);
  const second = Math.hypot(x3 - x2, y3 - y2);
  const along = rand() * (first + second);
  const [ax, ay, bx, by, u] =
    along < first ? [x1, y1, x2, y2, along / first] : [x2, y2, x3, y3, (along - first) / second];
  return [ax + (bx - ax) * u + (rand() - 0.5) * 5, ay + (by - ay) * u + (rand() - 0.5) * 5];
}

const DOTS: readonly Dot[] = (() => {
  const rand = random(7);
  const weighted = TRIANGLES.map(tri => ({ tri, area: area(tri) }));
  const total = weighted.reduce((s, w) => s + w.area, 0);
  const surface = Array.from({ length: SURFACE_DOTS }, () => onSurface(rand, weighted, total));
  return [...VERTICES, ...surface].map(p => {
    const away = rand() * Math.PI * 2;
    const distance = 130 + rand() * 120;
    const ring = rand() < 0.6;
    const [cx, cy] = inCheck(rand, ring);
    return {
      ...p,
      sx: CX + Math.cos(away) * distance,
      sy: CY + Math.sin(away) * distance,
      dir: (rand() - 0.5) * 1.2,
      far: 25 + rand() * 70,
      cx,
      cy,
      ring,
      lag: rand(),
    };
  });
})();

/** A face point on screen at a moment, the face turning left–right and nodding a little: x, y and depth (−1 … 1). */
function onScreen(p: Point, t: number): [number, number, number] {
  const yaw = 0.42 * Math.sin((2 * Math.PI * t) / 5200);
  const pitch = 0.08 * Math.sin((2 * Math.PI * t) / 3900);
  const x1 = p.x * Math.cos(yaw) + p.z * Math.sin(yaw);
  const z1 = -p.x * Math.sin(yaw) + p.z * Math.cos(yaw);
  const y2 = p.y * Math.cos(pitch) - z1 * Math.sin(pitch);
  const z2 = p.y * Math.sin(pitch) + z1 * Math.cos(pitch);
  const k = (FOCAL / (FOCAL - z2)) * SCALE;
  return [CX + x1 * k, CY - y2 * k, Math.max(-1, Math.min(1, z2 / 80))];
}

export const phaseAt = (t: number): ScanPhase => (t >= VERIFIED_FROM && t < VERIFIED_TO ? 'verified' : 'scan');

/** Where a dot is and how bright (0–1) at a moment of the loop, and whether the scan line is on it. */
function place(d: Dot, t: number, beamY: number): [number, number, number, boolean] {
  if (t < SCAN) {
    const [fx, fy, depth] = onScreen(d, t);
    const glow = 0.55 + 0.45 * depth;
    if (t >= GATHER) return [fx, fy, glow, Math.abs(fy - beamY) < 9];
    const u = easeOut(stagger(t / GATHER, d.lag));
    return [d.sx + (fx - d.sx) * u, d.sy + (fy - d.sy) * u, u * glow, false];
  }
  const [fx, fy] = onScreen(d, SCAN);
  const angle = Math.atan2(fy - CY, fx - CX) + d.dir;
  const [bx, by] = [fx + Math.cos(angle) * d.far, fy + Math.sin(angle) * d.far];
  if (t < BURST) {
    const u = easeOut(span(t, SCAN, BURST));
    return [fx + (bx - fx) * u, fy + (by - fy) * u, 0.9 - 0.35 * u, false];
  }
  // The ring turns slowly once formed.
  const spin = d.ring ? Math.max(0, t - RING) * 0.0005 : 0;
  const [ox, oy] = [d.cx - CX, d.cy - CY];
  const cx = CX + ox * Math.cos(spin) - oy * Math.sin(spin);
  const cy = CY + ox * Math.sin(spin) + oy * Math.cos(spin);
  if (t < RING) {
    const u = easeInOut(stagger(span(t, BURST, RING), d.lag));
    return [bx + (cx - bx) * u, by + (cy - by) * u, 0.55 + 0.45 * u, false];
  }
  if (t < HOLD) return [cx, cy, 0.8 + 0.2 * Math.sin(t / 180 + d.lag * 6), false];
  const u = easeIn(stagger(span(t, HOLD, LOOP_MS), d.lag));
  return [cx + (d.sx - cx) * u, cy + (d.sy - cy) * u, 1 - u, false];
}

const mix = (a: readonly number[], b: readonly number[], u: number) =>
  a.map((v, i) => Math.round(v + ((b[i] ?? v) - v) * u));
const rgba = (c: readonly number[], a: number) => `rgba(${c.join(', ')}, ${String(a)})`;

/** The viewfinder's four corners. */
function corners(ctx: CanvasRenderingContext2D, colour: string) {
  const [left, top, right, bottom, length] = [50, 22, 310, 248, 26];
  ctx.strokeStyle = colour;
  ctx.lineWidth = 3;
  ctx.lineCap = 'round';
  ctx.beginPath();
  for (const [x, y, dx, dy] of [
    [left, top, 1, 1],
    [right, top, -1, 1],
    [right, bottom, -1, -1],
    [left, bottom, 1, -1],
  ] as const) {
    ctx.moveTo(x, y + dy * length);
    ctx.lineTo(x, y);
    ctx.lineTo(x + dx * length, y);
  }
  ctx.stroke();
}

/** Draws moment `t` (ms) of the loop in the 360 × 300 drawing. */
function paint(ctx: CanvasRenderingContext2D, t: number) {
  // 0 → 1 as the dots turn from the face's blue to the check's green; the corners follow, and return as it leaves.
  const done = span(t, BURST, RING);
  const beamOn = t > BEAM_FROM && t < BEAM_TO;
  const beamY = CY - 100 + span(t, BEAM_FROM, BEAM_TO) * 208;

  corners(ctx, rgba(mix(WHITE, DONE, done * (1 - span(t, HOLD, LOOP_MS))), 0.55 + 0.25 * done));

  // The mesh, while the face is whole.
  const meshAlpha = 0.16 * span(t, GATHER - 300, GATHER + 300) * (1 - span(t, SCAN, SCAN + 250));
  if (meshAlpha > 0) {
    const at = VERTICES.map(p => onScreen(p, Math.min(t, SCAN)));
    ctx.strokeStyle = rgba(FACE, meshAlpha);
    ctx.lineWidth = 0.6;
    ctx.beginPath();
    for (const [a, b] of EDGES) {
      const pa = at[a];
      const pb = at[b];
      if (pa && pb) {
        ctx.moveTo(pa[0], pa[1]);
        ctx.lineTo(pb[0], pb[1]);
      }
    }
    ctx.stroke();
  }

  // The dots, one path per brightness step; those under the scan line light up.
  const steps = Array.from({ length: STEPS + 1 }, () => new Path2D());
  const lit = new Path2D();
  for (const d of DOTS) {
    const [x, y, glow, beam] = place(d, t, beamY);
    if (glow <= 0.02) continue;
    const k = Math.round(clamp(glow) * STEPS);
    const r = beam && beamOn ? 1.6 : 0.55 + k * 0.12;
    const path = beam && beamOn ? lit : steps[k];
    path?.moveTo(x + r, y);
    path?.arc(x, y, r, 0, Math.PI * 2);
  }
  const colour = mix(FACE, DONE, done);
  steps.forEach((path, k) => {
    ctx.fillStyle = rgba(colour, k / STEPS);
    ctx.fill(path);
  });
  ctx.fillStyle = 'rgba(255, 226, 170, 0.95)';
  ctx.fill(lit);

  // The scan line.
  if (beamOn) {
    const fade = Math.min(span(t, BEAM_FROM, BEAM_FROM + 150), 1 - span(t, BEAM_TO - 150, BEAM_TO));
    const band = ctx.createLinearGradient(0, beamY - 22, 0, beamY + 2);
    band.addColorStop(0, 'rgba(251, 176, 59, 0)');
    band.addColorStop(1, `rgba(251, 176, 59, ${String(0.35 * fade)})`);
    ctx.fillStyle = band;
    ctx.fillRect(78, beamY - 22, 204, 24);
    ctx.fillStyle = `rgba(255, 214, 140, ${String(0.9 * fade)})`;
    ctx.fillRect(78, beamY - 0.75, 204, 1.5);
  }

  // The landmarks, each lighting with a ripple, gone when the face scatters.
  const marksAlpha = 1 - span(t, SCAN, SCAN + 200);
  if (t > MARKS_FROM && marksAlpha > 0) {
    ctx.fillStyle = ORANGE;
    ctx.strokeStyle = ORANGE;
    ctx.lineWidth = 1;
    MARKS.forEach((n, i) => {
      const from = MARKS_FROM + (i * (MARKS_TO - MARKS_FROM)) / MARKS.length;
      const on = span(t, from, from + 120);
      if (on <= 0) return;
      const [x, y] = onScreen(vertex(n), Math.min(t, SCAN));
      const ripple = span(t, from, from + 500);
      ctx.globalAlpha = marksAlpha * on;
      ctx.beginPath();
      ctx.arc(x, y, 2.4, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = marksAlpha * 0.6 * (1 - ripple);
      ctx.beginPath();
      ctx.arc(x, y, 2.4 + 7 * ripple, 0, Math.PI * 2);
      ctx.stroke();
    });
    ctx.globalAlpha = 1;
  }

  // A soft light behind the check.
  const halo = span(t, BURST + 300, RING) * (1 - span(t, HOLD, HOLD + 400));
  if (halo > 0) {
    const light = ctx.createRadialGradient(CX, CY, 0, CX, CY, 106);
    light.addColorStop(0, rgba(DONE, 0.22 * halo));
    light.addColorStop(1, rgba(DONE, 0));
    ctx.fillStyle = light;
    ctx.fillRect(CX - 106, CY - 106, 212, 212);
  }
}

/**
 * A painter for `canvas`: draws moment `t` of the loop sized to the canvas's box and the screen's pixel density, with
 * a blurred copy added on top for the glow. Null where the browser has no 2D canvas.
 */
export function scanPainter(canvas: HTMLCanvasElement): ((t: number) => void) | null {
  const ctx = canvas.getContext('2d');
  const layer = document.createElement('canvas');
  const lctx = layer.getContext('2d');
  if (!ctx || !lctx) return null;
  return t => {
    const ratio = window.devicePixelRatio || 1;
    const width = Math.round(canvas.clientWidth * ratio);
    const height = Math.round(canvas.clientHeight * ratio);
    if (!width || !height) return;
    for (const c of [canvas, layer]) {
      if (c.width !== width || c.height !== height) {
        c.width = width;
        c.height = height;
      }
    }
    lctx.setTransform(width / W, 0, 0, height / H, 0, 0);
    lctx.clearRect(0, 0, W, H);
    paint(lctx, t);
    ctx.clearRect(0, 0, width, height);
    ctx.globalCompositeOperation = 'lighter';
    ctx.filter = `blur(${String(3 * ratio)}px)`;
    ctx.drawImage(layer, 0, 0);
    ctx.filter = 'none';
    ctx.drawImage(layer, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
  };
}
