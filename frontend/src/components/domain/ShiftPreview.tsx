/**
 * The prototype's shiftPreviewSVG: the check-in window, the shift with its late and early-exit grace, and day close on
 * one line, with the times marked. Takes the API's rule names, so a shift version can be passed as it is.
 */
import { Legend } from '@/components/charts/Legend';
import { fmtHm } from '@/lib/format';
import '@/components/charts/Legend.module.css';

export interface ShiftShape {
  start: string;
  end: string;
  windowBeforeHours: number;
  dayCloseHours: number;
  lateGraceMinutes: number;
  earlyGraceMinutes: number;
}

const W = 640;
const minutes = (hm: string) => {
  const [h = 0, m = 0] = hm.split(':').map(Number);
  return h * 60 + m;
};
const label = (m: number) => {
  const day = ((m % 1440) + 1440) % 1440;
  return fmtHm(`${String(Math.floor(day / 60)).padStart(2, '0')}:${String(day % 60).padStart(2, '0')}`);
};

export function ShiftPreview({ shift: sv, colour }: { shift: ShiftShape; colour: string }) {
  const s = minutes(sv.start);
  let e = minutes(sv.end);
  if (e <= s) e += 1440;
  const a = s - sv.windowBeforeHours * 60;
  const b = e + sv.dayCloseHours * 60;
  const x = (m: number) => 10 + ((W - 20) * (m - a)) / (b - a);
  const mark = (m: number, text: string, y: number, anchor: 'start' | 'middle' | 'end' = 'middle') => (
    <g key={text}>
      <line x1={x(m)} x2={x(m)} y1="20" y2="46" stroke="var(--text-3)" strokeDasharray="2 2" />
      <text x={x(m)} y={y} fontSize="10.5" textAnchor={anchor} fill="var(--text-2)">
        {text}
      </text>
    </g>
  );
  return (
    <>
      <svg
        viewBox={`0 0 ${String(W)} 74`}
        style={{ width: '100%', height: 'auto' }}
        role="img"
        aria-label={`Shift timeline: ${label(s)} to ${label(e)}`}
      >
        <rect x={x(a)} y="26" width={x(s) - x(a)} height="14" fill="var(--surface-3)" />
        <rect x={x(s)} y="26" width={x(e) - x(s)} height="14" rx="2" fill={colour} opacity=".85" />
        <rect x={x(e)} y="26" width={x(b) - x(e)} height="14" fill="var(--surface-3)" />
        <rect
          x={x(s)}
          y="26"
          width={x(s + sv.lateGraceMinutes) - x(s)}
          height="14"
          fill="var(--amber-line)"
          opacity=".7"
        />
        <rect
          x={x(e - sv.earlyGraceMinutes)}
          y="26"
          width={x(e) - x(e - sv.earlyGraceMinutes)}
          height="14"
          fill="var(--amber-line)"
          opacity=".7"
        />
        {mark(a, `Window opens ${label(a)}`, 14, 'start')}
        {mark(s, `Start ${label(s)}`, 60)}
        {mark(e, `End ${label(e)}`, 60)}
        {mark(b, `Day close ${label(b)}`, 14, 'end')}
      </svg>
      <Legend
        className="mt-4"
        entries={[
          { colour: 'var(--surface-3)', label: 'Check-in window / until day close' },
          { colour: 'var(--amber-line)', label: 'Late and early-exit grace' },
          ...(e > 1440 ? [{ label: 'Ends the next day — punches count for the day the shift started' }] : []),
        ]}
      />
    </>
  );
}
