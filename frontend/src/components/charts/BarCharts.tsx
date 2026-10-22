/**
 * The prototype's hBarChart and stackedBarChart, the same SVG: horizontal bars with the value after each (the
 * reviewers' decisions), and one stacked bar per row with the first segment's share (coverage by department,
 * recognition per day).
 */
import { niceMax } from './scale';
import './Chart.module.css';

export interface Bar {
  label: string;
  value: number;
  color?: string;
}

export function HBarChart({
  items,
  color = 'var(--accent)',
  format = v => String(v),
}: {
  items: readonly Bar[];
  color?: string;
  format?: (value: number) => string;
}) {
  const W = 760;
  const rowH = 28;
  const L = 170;
  const R = 60;
  const H = items.length * rowH + 10;
  const max = niceMax(Math.max(1, ...items.map(i => i.value)));
  return (
    <svg viewBox={`0 0 ${String(W)} ${String(H)}`} role="img" aria-label="Bar chart">
      {items.map((it, i) => {
        const w = Math.max(2, ((W - L - R) * it.value) / max);
        const y = 6 + i * rowH;
        return (
          <g key={it.label}>
            <text x={L - 10} y={y + 15} textAnchor="end" fontSize="12" fill="var(--text-2)">
              {it.label}
            </text>
            <rect x={L} y={y + 3} width={W - L - R} height="16" rx="3" fill="var(--surface-3)" />
            <rect x={L} y={y + 3} width={w} height="16" rx="3" fill={it.color ?? color}>
              <title>
                {it.label}: {format(it.value)}
              </title>
            </rect>
            <text x={L + w + 6} y={y + 15} fontSize="12" fontWeight="600" fill="var(--text)">
              {format(it.value)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/** Gap before the stacked bar chart's share text, and a 12 px label's average character width in viewBox units. */
const SHARE_GAP = 8;
const CHAR_W = 6.8;

export interface Segment {
  key: string;
  label: string;
  color: string;
}

export function StackedBarChart({
  items,
  segments,
}: {
  items: readonly { label: string; values: Readonly<Record<string, number>> }[];
  segments: readonly Segment[];
}) {
  const W = 760;
  const rowH = 30;
  const L = 130;
  const H = items.length * rowH + 10;
  const first = segments[0];
  const share = (values: Readonly<Record<string, number>>) => {
    const total = segments.reduce((sum, s) => sum + (values[s.key] ?? 0), 0);
    return `${String(total && first ? Math.round((100 * (values[first.key] ?? 0)) / total) : 0)}% ${first?.label.toLowerCase() ?? ''}`;
  };
  // The prototype keeps 70 units right of the bars for the share text, but "33% face (auto)" needs about 105, so the
  // text was cut off at the card's edge (D-119). The bars give up the room the longest text needs.
  const R = Math.max(70, SHARE_GAP + Math.ceil(Math.max(0, ...items.map(it => share(it.values).length)) * CHAR_W));
  return (
    <svg viewBox={`0 0 ${String(W)} ${String(H)}`} role="img" aria-label="Stacked bar chart">
      {items.map((it, i) => {
        const value = (key: string) => it.values[key] ?? 0;
        const total = segments.reduce((sum, s) => sum + value(s.key), 0);
        const y = 6 + i * rowH;
        let x = L;
        return (
          <g key={it.label}>
            <text x={L - 10} y={y + 15} textAnchor="end" fontSize="12" fill="var(--text-2)">
              {it.label}
            </text>
            {segments.map(s => {
              const w = total ? ((W - L - R) * value(s.key)) / total : 0;
              const rect = (
                <rect key={s.key} x={x} y={y + 3} width={Math.max(0, w - 1)} height="17" fill={s.color}>
                  <title>
                    {it.label} · {s.label}: {value(s.key)}
                  </title>
                </rect>
              );
              x += w;
              return rect;
            })}
            <text x={W - R + SHARE_GAP} y={y + 15} fontSize="12" fill="var(--text-2)">
              {share(it.values)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
