/**
 * Drawing Live View overlays (F7, the prototype's drawWire and drawWalker labels): tripwire with the "Inside" arrow
 * (or, on a doorway-zone camera, the door area; D-120) and the zone from the camera's configuration, boxes and labels
 * from the overlay stream. Pure functions on a 2D
 * context, so the tile canvas and the snapshot draw exactly the same picture.
 */
import type { LiveTile, OverlayFrame, OverlayTrack } from '@/api/live';
import { readPreference, writePreference } from './storage';
import { trackName } from './text';

/** The prototype's seven OVERLAY_OPTIONS (F6.7). */
export interface OverlayOptions {
  boxes: boolean;
  names: boolean;
  track: boolean;
  conf: boolean;
  wire: boolean;
  zone: boolean;
  quality: boolean;
}

export const OVERLAY_OPTIONS: readonly (readonly [keyof OverlayOptions, string])[] = [
  ['boxes', 'Bounding boxes'],
  ['names', 'Names'],
  ['track', 'Track ID'],
  ['conf', 'Confidence band'],
  ['wire', 'Tripwire or door area'],
  ['zone', 'Detection zone'],
  ['quality', 'Face-quality badge'],
];

export const DEFAULT_OVERLAYS: OverlayOptions = {
  boxes: true,
  names: true,
  track: false,
  conf: true,
  wire: true,
  zone: false,
  quality: false,
};

/** Per-user choices kept in this browser (F6.7): the global set and per-tile overrides ("Use global settings"). */
export function readOverlayPrefs(): { global: OverlayOptions; tiles: Record<string, Partial<OverlayOptions>> } {
  return {
    global: { ...DEFAULT_OVERLAYS, ...readPreference<Partial<OverlayOptions>>('overlays', {}) },
    tiles: readPreference<Record<string, Partial<OverlayOptions>>>('tile-overlays', {}),
  };
}

export function writeOverlayPrefs(global: OverlayOptions, tiles: Record<string, Partial<OverlayOptions>>): void {
  writePreference('overlays', global);
  writePreference('tile-overlays', tiles);
}

/** The prototype's BOX_COLOURS by the stream's track state. */
const COLOURS: Record<OverlayTrack['state'], string> = {
  identified: '#2fbf71',
  reid: '#a07cf5',
  unknown: '#9aa3af',
  checking: '#f0a945',
  liveness: '#f06a5e',
};
const BAND: Record<NonNullable<OverlayTrack['conf_band']>, string> = { high: 'High', medium: 'Medium', low: 'Low' };
const QUALITY: Record<NonNullable<OverlayTrack['quality']>, string> = { good: 'Good', fair: 'Fair', poor: 'Low' };
const WIRE = '#38bdf8';

/** Where the picture is inside the element (object-fit: contain): offset and size in canvas pixels. */
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export function containRect(boxW: number, boxH: number, mediaW: number, mediaH: number): Rect {
  if (!mediaW || !mediaH) return { x: 0, y: 0, w: boxW, h: boxH };
  const scale = Math.min(boxW / mediaW, boxH / mediaH);
  const w = mediaW * scale;
  const h = mediaH * scale;
  return { x: (boxW - w) / 2, y: (boxH - h) / 2, w, h };
}

const at = (r: Rect, x: number, y: number): [number, number] => [r.x + x * r.w, r.y + y * r.h];

/**
 * The zone, and the camera's counting drawing: the tripwire with its "Inside" arrow, or the door area of a
 * doorway-zone camera (Live View tiles and the camera editor).
 */
export function drawGeometry(
  ctx: CanvasRenderingContext2D,
  camera: Pick<LiveTile, 'zone' | 'wire' | 'countingMethod' | 'doorZone'>,
  r: Rect,
  options: Pick<OverlayOptions, 'zone' | 'wire'>,
): void {
  if (options.zone && camera.zone.length) {
    ctx.setLineDash([8, 6]);
    ctx.strokeStyle = 'rgba(250,204,21,.8)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    camera.zone.forEach(([x = 0, y = 0], i) => {
      const [px, py] = at(r, x, y);
      if (i) ctx.lineTo(px, py);
      else ctx.moveTo(px, py);
    });
    ctx.closePath();
    ctx.stroke();
    ctx.fillStyle = 'rgba(250,204,21,.06)';
    ctx.fill();
    ctx.setLineDash([]);
  }
  if (camera.countingMethod === 'DOORWAY_ZONE') {
    if (options.wire) drawDoor(ctx, camera.doorZone, r);
    return;
  }
  const wire = camera.wire;
  if (!options.wire || !wire) return;
  const [ax, ay] = at(r, wire.a[0] ?? 0, wire.a[1] ?? 0);
  const [bx, by] = at(r, wire.b[0] ?? 0, wire.b[1] ?? 0);
  ctx.strokeStyle = WIRE;
  ctx.lineWidth = Math.max(2, r.w * 0.003);
  ctx.beginPath();
  ctx.moveTo(ax, ay);
  ctx.lineTo(bx, by);
  ctx.stroke();
  const mx = (ax + bx) / 2;
  const my = (ay + by) / 2;
  const length = Math.hypot(by - ay, bx - ax) || 1;
  const nx = (-(by - ay) / length) * wire.inSide;
  const ny = ((bx - ax) / length) * wire.inSide;
  const tx = mx + nx * r.h * 0.09;
  const ty = my + ny * r.h * 0.09;
  ctx.beginPath();
  ctx.moveTo(mx, my);
  ctx.lineTo(tx, ty);
  ctx.stroke();
  ctx.fillStyle = WIRE;
  ctx.beginPath();
  ctx.moveTo(tx + nx * 8, ty + ny * 8);
  ctx.lineTo(tx - ny * 7, ty + nx * 7);
  ctx.lineTo(tx + ny * 7, ty - nx * 7);
  ctx.fill();
  const fs = Math.max(11, r.h * 0.036);
  ctx.font = `700 ${fs}px system-ui`;
  const text = 'Inside';
  const lw = ctx.measureText(text).width;
  const lx = tx + nx * 16 + 8;
  const ly = ty + ny * 16;
  ctx.fillRect(lx - 4, ly - fs / 2 - 3, lw + 8, fs + 4);
  ctx.fillStyle = '#0b1220';
  ctx.fillText(text, lx, ly + fs / 2 - 2);
}

/** The door area: an outline in the tripwire's colour with a "Door" tag at its top. */
function drawDoor(ctx: CanvasRenderingContext2D, door: readonly (readonly number[])[], r: Rect): void {
  if (door.length < 3) return;
  ctx.strokeStyle = WIRE;
  ctx.lineWidth = Math.max(2, r.w * 0.003);
  ctx.beginPath();
  door.forEach(([x = 0, y = 0], i) => {
    const [px, py] = at(r, x, y);
    if (i) ctx.lineTo(px, py);
    else ctx.moveTo(px, py);
  });
  ctx.closePath();
  ctx.fillStyle = 'rgba(56,189,248,.10)';
  ctx.fill();
  ctx.stroke();
  const [tx, ty] = at(r, Math.min(...door.map(p => p[0] ?? 0)), Math.min(...door.map(p => p[1] ?? 0)));
  const fs = Math.max(11, r.h * 0.036);
  ctx.font = `700 ${fs}px system-ui`;
  const text = 'Door';
  const lw = ctx.measureText(text).width;
  ctx.fillStyle = WIRE;
  ctx.fillRect(tx, ty, lw + 8, fs + 4);
  ctx.fillStyle = '#0b1220';
  ctx.fillText(text, tx + 4, ty + fs);
}

/** The label as the prototype builds it: name (or Known / Re-ID match without names), band, track ID, direction. */
export function trackLabel(track: OverlayTrack, options: OverlayOptions): string {
  let label = track.label;
  if (!options.names && (track.state === 'identified' || track.state === 'reid')) {
    label = track.state === 'reid' ? 'Re-ID match' : 'Known';
  }
  if (options.conf && track.conf_band && track.state !== 'checking') label += ` · ${BAND[track.conf_band]}`;
  if (options.track) label += ` · #${trackName(track.id)}`;
  if (track.direction) label += ` · ${track.direction}`;
  return label;
}

function fitText(ctx: CanvasRenderingContext2D, text: string, max: number): string {
  if (ctx.measureText(text).width <= max) return text;
  let cut = text;
  while (cut.length > 1 && ctx.measureText(`${cut}…`).width > max) cut = cut.slice(0, -1);
  return `${cut}…`;
}

/** A drawn box and its full label, for the hover tooltip (F6.4). */
export interface Hit {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
  label: string;
}

export function drawTracks(
  ctx: CanvasRenderingContext2D,
  frame: OverlayFrame,
  r: Rect,
  options: OverlayOptions,
  canvasWidth: number,
): Hit[] {
  if (!options.boxes) return [];
  const hits: Hit[] = [];
  for (const track of frame.tracks) {
    const [x, y, w, h] = track.bbox;
    const [x0, y0] = at(r, x, y);
    const [x1, y1] = at(r, x + w, y + h);
    const colour = COLOURS[track.state];
    ctx.strokeStyle = colour;
    ctx.lineWidth = Math.max(2, canvasWidth * 0.0028);
    ctx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    const label = trackLabel(track, options);
    hits.push({ x0, y0, x1, y1, label });
    const fs = Math.max(10, Math.min(15, (y1 - y0) * 0.07)) * Math.max(1, canvasWidth / 900);
    ctx.font = `600 ${fs}px system-ui, sans-serif`;
    const shown = fitText(ctx, label, Math.max(x1 - x0, fs * 12));
    const tw = ctx.measureText(shown).width;
    const lx = Math.min(Math.max(x0, 2), canvasWidth - tw - 12);
    const ly = Math.max(fs + 6, y0 - 4);
    ctx.fillStyle = colour;
    ctx.fillRect(lx, ly - fs - 5, tw + 10, fs + 7);
    ctx.fillStyle = track.state === 'unknown' || track.state === 'checking' ? '#111418' : '#ffffff';
    ctx.fillText(shown, lx + 5, ly - 3);
    if (options.quality && track.quality && track.state !== 'checking') {
      const good = track.quality === 'good';
      const text = `Face Q: ${QUALITY[track.quality]}`;
      ctx.font = `600 ${fs * 0.8}px system-ui`;
      const qw = ctx.measureText(text).width;
      ctx.fillStyle = good ? 'rgba(18,48,31,.85)' : 'rgba(51,38,15,.85)';
      ctx.fillRect(x0, y1 - fs * 1.2, qw + 8, fs * 1.1);
      ctx.fillStyle = good ? '#5fd394' : '#f3b25a';
      ctx.fillText(text, x0 + 4, y1 - fs * 0.35);
    }
  }
  return hits;
}
