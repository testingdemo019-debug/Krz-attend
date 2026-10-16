import { toMillis, fmtTime, type Instant } from '@/lib/format';
import { Legend } from './Legend';
import './Chart.module.css';

export interface TimelineCrossing {
  at: Instant;
  direction: string;
  /** The rule outcome; null, ignored or voided crossings are drawn dashed. */
  effect: string | null;
  label: string;
}

const W = 640;
const H = 64;
const COUNTED = new Set(['CHECK_IN', 'CHECK_OUT', 'MOVEMENT']);
const HOUR = 3_600_000;

/**
 * The prototype's dayTimelineSVG: the shift day from the check-in window to day close, the shift band, the time
 * inside (counted IN to OUT), a tick per crossing (IN above, OUT below, dashed when ignored), "now" while the day is
 * open, hour labels every three hours.
 */
export function DayTimeline({
  windowStart,
  start,
  end,
  close,
  crossings,
  finalised,
  now,
}: {
  windowStart: Instant;
  start: Instant;
  end: Instant;
  close: Instant;
  crossings: readonly TimelineCrossing[];
  finalised: boolean;
  now: number;
}) {
  const a = toMillis(windowStart);
  const b = toMillis(close);
  const x = (t: number): number => 10 + (W - 20) * Math.min(Math.max((t - a) / (b - a), 0), 1);
  const segments: [number, number][] = [];
  let open: number | null = null;
  for (const c of crossings) {
    if (!c.effect || !COUNTED.has(c.effect)) continue;
    const t = toMillis(c.at);
    if (c.direction === 'IN') open ??= t;
    else if (open !== null) {
      segments.push([open, t]);
      open = null;
    }
  }
  if (open !== null) segments.push([open, finalised ? open : Math.min(now, b)]);
  const ticks: number[] = [];
  for (let h = Math.ceil(a / HOUR) * HOUR; h <= b; h += 3 * HOUR) ticks.push(h);
  const s = toMillis(start);
  const e = toMillis(end);

  return (
    <>
      <svg
        viewBox={`0 0 ${String(W)} ${String(H)}`}
        role="img"
        aria-label="Shift day timeline"
        style={{ width: '100%', height: 'auto' }}
      >
        <rect x="10" y="26" width={W - 20} height="12" rx="3" fill="var(--surface-3)" />
        <rect
          x={x(s)}
          y="26"
          width={x(e) - x(s)}
          height="12"
          fill="var(--accent-weak)"
          stroke="var(--accent)"
          strokeWidth=".6"
        />
        {segments.map(([from, to]) => (
          <rect
            key={from}
            x={x(from)}
            y="29"
            width={Math.max(2, x(to) - x(from))}
            height="6"
            rx="2"
            fill="var(--green-line)"
          >
            <title>
              Inside {fmtTime(from)}–{fmtTime(to)}
            </title>
          </rect>
        ))}
        {crossings.map((c, i) => {
          const t = toMillis(c.at);
          const ignored = !c.effect || c.effect.startsWith('IGNORED') || c.effect === 'VOIDED';
          const isIn = c.direction === 'IN';
          return (
            <g key={`${String(t)}-${String(i)}`}>
              <line
                x1={x(t)}
                x2={x(t)}
                y1={isIn ? 14 : 40}
                y2={isIn ? 26 : 50}
                stroke={ignored ? 'var(--text-3)' : isIn ? 'var(--green)' : 'var(--accent-text)'}
                strokeWidth="2"
                {...(ignored ? { strokeDasharray: '2 2' } : {})}
              />
              <title>
                {c.direction} {fmtTime(t, true)} · {c.label}
              </title>
            </g>
          );
        })}
        {!finalised && now < b && now > a && (
          <>
            <line x1={x(now)} x2={x(now)} y1="8" y2="56" stroke="var(--red-line, #e5484d)" strokeWidth="1.5" />
            <text x={x(now) + 3} y="12" fontSize="10" fill="var(--text-3)">
              now
            </text>
          </>
        )}
        {ticks.map((h, i) => (
          <text
            key={h}
            x={x(h)}
            y={H - 2}
            fontSize="10"
            textAnchor={i === 0 ? 'start' : x(h) > W - 50 ? 'end' : 'middle'}
            fill="var(--text-3)"
          >
            {fmtTime(h).replace(':00', '')}
          </text>
        ))}
      </svg>
      <Legend
        className="mt-4"
        entries={[
          {
            label: `Shift ${fmtTime(s)}–${fmtTime(e)}`,
            colour: 'var(--accent-weak)',
            border: '1px solid var(--accent)',
          },
          { label: 'Inside', colour: 'var(--green-line)' },
          { label: 'Ticks above: IN · below: OUT · dashed: ignored' },
        ]}
      />
    </>
  );
}
