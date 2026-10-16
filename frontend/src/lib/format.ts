/**
 * Dates and times exactly as the prototype shows them ("Utilities" section): "9:42 AM" or "09:42", "12 Oct 2026",
 * "Mon, 12 Oct 2026", "3 min ago", "8h 05m". The time zone and the 12h/24h setting come from /meta (configured once
 * by the AuthGate); every day boundary is computed in that zone, never in the browser's.
 */

export interface FormatSettings {
  timeZone: string;
  hour12: boolean;
}

/** A timestamp from the API (ISO instant), epoch milliseconds or a Date. */
export type Instant = string | number | Date;
/** A calendar day, "2026-10-12". */
export type Day = string;

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;
export const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
/** Monday first, as the prototype lists weekdays (0 = Sunday). */
export const WEEKDAY_ORDER = [1, 2, 3, 4, 5, 6, 0] as const;
export const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
] as const;
export const DASH = '—';

let settings: FormatSettings = { timeZone: 'Asia/Kolkata', hour12: true };
let partsFormat = makePartsFormat(settings.timeZone);

export function configureFormat(next: FormatSettings): void {
  if (next.timeZone !== settings.timeZone) partsFormat = makePartsFormat(next.timeZone);
  settings = next;
}

export function formatSettings(): FormatSettings {
  return settings;
}

function makePartsFormat(timeZone: string): Intl.DateTimeFormat {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
}

export interface ZonedParts {
  y: number;
  /** 1–12 */
  mo: number;
  d: number;
  h: number;
  mi: number;
  s: number;
}

export function toMillis(value: Instant): number {
  return typeof value === 'number' ? value : typeof value === 'string' ? Date.parse(value) : value.getTime();
}

/** The wall-clock parts of an instant in the configured time zone. */
export function zonedParts(value: Instant): ZonedParts {
  const parts: Partial<Record<Intl.DateTimeFormatPartTypes, number>> = {};
  for (const part of partsFormat.formatToParts(toMillis(value))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return {
    y: parts.year ?? 0,
    mo: parts.month ?? 0,
    d: parts.day ?? 0,
    h: parts.hour ?? 0,
    mi: parts.minute ?? 0,
    s: parts.second ?? 0,
  };
}

const pad = (n: number): string => String(n).padStart(2, '0');

/** The configured zone's calendar day of an instant. */
export function dayOf(value: Instant): Day {
  const p = zonedParts(value);
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}`;
}

export function todayDay(now: number = Date.now()): Day {
  return dayOf(now);
}

export function addDays(day: Day, n: number): Day {
  return new Date(Date.parse(`${day}T00:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);
}

/** 0 = Sunday … 6 = Saturday. */
export function weekdayOf(day: Day): number {
  return new Date(`${day}T00:00:00Z`).getUTCDay();
}

export function daysBetween(a: Day, b: Day): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

function clock(h: number, mi: number, s: number | null): string {
  const mm = pad(mi);
  const ss = s === null ? '' : `:${pad(s)}`;
  if (!settings.hour12) return `${pad(h)}:${mm}${ss}`;
  return `${h % 12 || 12}:${mm}${ss} ${h < 12 ? 'AM' : 'PM'}`;
}

/** A time of day given in seconds since midnight, with the seconds only when there are some (the rule tester). */
export function fmtClock(seconds: number): string {
  const s = seconds % 60;
  return clock(Math.floor(seconds / 3600) % 24, Math.floor((seconds % 3600) / 60), s ? s : null);
}

export function fmtTime(value: Instant | null | undefined, withSeconds = false): string {
  if (value === null || value === undefined) return DASH;
  const p = zonedParts(value);
  return clock(p.h, p.mi, withSeconds ? p.s : null);
}

/** A time of day such as a shift start ("09:30"), in the 12h/24h setting. */
export function fmtHm(hm: string): string {
  const [h = 0, m = 0] = hm.split(':').map(Number);
  return clock(h, m, null);
}

/**
 * The prototype's shiftTimingText for a shift being edited: "9:30 AM–6:30 PM", "+1 day" when it ends the next day.
 * (Saved shifts carry the server's text.)
 */
export function fmtShiftTiming(start: string, end: string): string {
  const m = (hm: string) => {
    const [h = 0, mi = 0] = hm.split(':').map(Number);
    return h * 60 + mi;
  };
  return `${fmtHm(start)}–${fmtHm(end)}${m(end) <= m(start) ? ' (+1 day)' : ''}`;
}

/** The prototype's weeklyOffText: "Sat, Sun" (Monday first), or "None". */
export function fmtWeeklyOffs(days: readonly number[]): string {
  const list = WEEKDAY_ORDER.filter(d => days.includes(d)).map(d => WEEKDAY_LABELS[d]);
  return list.length ? list.join(', ') : 'None';
}

function dayParts(day: Day): [number, number, number] {
  const [y = 0, m = 1, d = 1] = day.split('-').map(Number);
  return [y, m, d];
}

/** "12 Oct 2026" from a day or an instant. */
export function fmtDate(value: Day | Instant | null | undefined): string {
  if (value === null || value === undefined || value === '') return DASH;
  const day = typeof value === 'string' && value.length === 10 ? value : dayOf(value);
  const [y, m, d] = dayParts(day);
  return `${d} ${MONTH_LABELS[m - 1] ?? ''} ${y}`;
}

/** "12 Oct". */
export function fmtDateShort(day: Day): string {
  const [, m, d] = dayParts(day);
  return `${d} ${MONTH_LABELS[m - 1] ?? ''}`;
}

/** "12 Oct 2026, 9:42 AM". */
export function fmtDateTime(value: Instant | null | undefined): string {
  return value === null || value === undefined ? DASH : `${fmtDate(value)}, ${fmtTime(value)}`;
}

/** "Mon, 12 Oct 2026". */
export function fmtDay(day: Day): string {
  return `${WEEKDAY_LABELS[weekdayOf(day)] ?? ''}, ${fmtDate(day)}`;
}

/** The attendance log's column heading: "Mo 12". */
export function fmtDayColumn(day: Day): string {
  return `${(WEEKDAY_LABELS[weekdayOf(day)] ?? '').slice(0, 2)} ${String(Number(day.slice(8)))}`;
}

/** "8h 05m" from minutes. */
export function fmtDuration(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return DASH;
  const total = Math.max(0, Math.round(minutes));
  return `${Math.floor(total / 60)}h ${pad(total % 60)}m`;
}

/** "just now", "3 min ago", "2 h ago", then the date; future times read "in 0h 05m". */
export function relTime(value: Instant, now: number = Date.now()): string {
  const seconds = Math.round((now - toMillis(value)) / 1000);
  if (seconds < 0) return `in ${fmtDuration(-seconds / 60)}`;
  if (seconds < 45) return 'just now';
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} h ago`;
  return fmtDate(value);
}

/** The name the server gives an export (CsvExportService): "review-history-2026-10-12-1042.csv". */
export function exportFileName(name: string, now: number = Date.now()): string {
  const p = zonedParts(now);
  return `${name}-${String(p.y)}-${pad(p.mo)}-${pad(p.d)}-${pad(p.h)}${pad(p.mi)}.csv`;
}

/** The minute of the day in the configured zone (0–1439). */
export function minuteOfDay(value: Instant): number {
  const p = zonedParts(value);
  return p.h * 60 + p.mi;
}

/** "Good morning" / "afternoon" / "evening" in the configured zone (F6.5), not the browser's. */
export function greeting(now: number = Date.now()): string {
  const h = zonedParts(now).h;
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

/** The zone as the prototype names it in sub-lines ("times in IST"): IST for India, else the zone's id. */
export function zoneLabel(): string {
  return settings.timeZone === 'Asia/Kolkata' ? 'IST' : settings.timeZone;
}

/** An hour of the day as a chart label: "6a"/"6p" in 12h, "06" in 24h (the prototype's crossings chart). */
export function hourLabel(hour: number): string {
  return settings.hour12 ? `${hour % 12 || 12}${hour < 12 ? 'a' : 'p'}` : String(hour).padStart(2, '0');
}
