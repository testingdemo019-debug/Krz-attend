import { niceMax } from './scale';
import './Chart.module.css';

export interface ChartMarker {
  index: number;
  label: string;
}

/**
 * The prototype's inOutChart: IN (green) and OUT (accent) bars per slot, dashed average lines, gridlines at 0, half
 * and max, optional markers ("now"); every second label shown. Same SVG, same viewBox.
 */
export function InOutChart({
  labels,
  inBars,
  outBars,
  inAvg,
  outAvg,
  height = 230,
  title = 'Arrivals and departures by hour',
  markers = [],
}: {
  labels: readonly string[];
  inBars: readonly number[];
  outBars: readonly number[];
  inAvg: readonly number[];
  outAvg: readonly number[];
  height?: number;
  title?: string;
  markers?: readonly ChartMarker[];
}) {
  const W = 760;
  const H = height;
  const L = 34;
  const R = 10;
  const T = 14;
  const B = 34;
  const max = niceMax(Math.max(1, ...inBars, ...outBars, ...inAvg, ...outAvg));
  const bw = (W - L - R) / labels.length;
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  const points = (values: readonly number[]) => values.map((v, i) => `${L + bw * i + bw / 2},${y(v)}`).join(' ');
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title}>
      {[0, max / 2, max].map(t => (
        <g key={t}>
          <line x1={L} x2={W - R} y1={y(t)} y2={y(t)} stroke="var(--chart-grid)" />
          <text x={L - 6} y={y(t) + 4} textAnchor="end" fontSize="11" fill="var(--text-3)">
            {Math.round(t)}
          </text>
        </g>
      ))}
      {markers.map(mk => (
        <g key={mk.index}>
          <line
            x1={L + bw * mk.index}
            x2={L + bw * mk.index}
            y1={T}
            y2={H - B}
            stroke="var(--amber-line)"
            strokeDasharray="3 3"
          />
          <text x={L + bw * mk.index + 3} y={T + 9} fontSize="10" fill="var(--amber)">
            {mk.label}
          </text>
        </g>
      ))}
      {labels.map((label, i) => {
        const inV = inBars[i] ?? 0;
        const outV = outBars[i] ?? 0;
        return (
          <g key={label}>
            <rect
              x={L + bw * i + bw * 0.14}
              y={y(inV)}
              width={bw * 0.34}
              height={H - B - y(inV)}
              rx="2"
              fill="var(--green-line)"
            >
              <title>{`${label}: ${inV} IN`}</title>
            </rect>
            <rect
              x={L + bw * i + bw * 0.52}
              y={y(outV)}
              width={bw * 0.34}
              height={H - B - y(outV)}
              rx="2"
              fill="var(--accent)"
            >
              <title>{`${label}: ${outV} OUT`}</title>
            </rect>
          </g>
        );
      })}
      <polyline
        points={points(inAvg)}
        fill="none"
        stroke="var(--green)"
        strokeWidth="2"
        strokeDasharray="4 3"
        opacity=".9"
      />
      <polyline
        points={points(outAvg)}
        fill="none"
        stroke="var(--accent-text)"
        strokeWidth="2"
        strokeDasharray="4 3"
        opacity=".9"
      />
      {labels.map((label, i) =>
        i % 2 === 0 ? (
          <text
            key={label}
            x={L + bw * i + bw / 2}
            y={H - B + 16}
            textAnchor="middle"
            fontSize="10.5"
            fill="var(--text-3)"
          >
            {label}
          </text>
        ) : null,
      )}
    </svg>
  );
}
