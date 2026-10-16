/** The prototype's poseSVG and tipSVG: the pose pictograms of the capture strip and the "Get ready" tips. */
import type { ReactNode } from 'react';
import type { PoseKey } from './guidance';

export function PoseIcon({ kind, color = 'currentColor' }: { kind: PoseKey; color?: string }) {
  const yaw = kind === 'left' ? -1 : kind === 'right' ? 1 : 0;
  const down = kind === 'down';
  const nx = 12 + yaw * 3.2;
  const ny = down ? 14.5 : 12.5;
  const eyeY = down ? 12 : 10.5;
  return (
    <svg viewBox="0 0 24 24" className="ph" aria-hidden="true">
      <ellipse cx="12" cy="12" rx="7" ry="8.5" fill="none" stroke={color} strokeWidth="1.6" />
      <circle cx={9.5 + yaw * 2.4} cy={eyeY} r="1" fill={color} />
      <circle cx={14.5 + yaw * 2.4} cy={eyeY} r="1" fill={color} />
      <path
        d={`M${nx} ${ny - 1.5} l${yaw * 0.8} 2`}
        stroke={color}
        strokeWidth="1.3"
        fill="none"
        strokeLinecap="round"
      />
      {yaw !== 0 && (
        <path
          d={`M${12 + yaw * 9} 12 l${yaw * 2.5} 0 M${12 + yaw * 11.5} 12 l${-yaw * 1.3} -1.3 M${12 + yaw * 11.5} 12 l${-yaw * 1.3} 1.3`}
          stroke={color}
          strokeWidth="1.4"
          fill="none"
          strokeLinecap="round"
        />
      )}
      {down && (
        <path
          d="M12 21.5 v2 M12 23.5 l-1.3-1.3 M12 23.5 l1.3-1.3"
          stroke={color}
          strokeWidth="1.4"
          fill="none"
          strokeLinecap="round"
        />
      )}
    </svg>
  );
}

function Face({ x = 32, colour = '#d9a27a', children }: { x?: number; colour?: string; children?: ReactNode }) {
  return (
    <>
      <ellipse cx={x} cy="30" rx="11" ry="13.5" fill={colour} />
      <path
        d={`M${x - 11} 28 C${x - 11} 14 ${x + 11} 14 ${x + 11} 28 C${x + 8} 21 ${x - 8} 21 ${x - 11} 28Z`}
        fill="#2a1e16"
      />
      <circle cx={x - 4} cy="30" r="1.2" fill="#222" />
      <circle cx={x + 4} cy="30" r="1.2" fill="#222" />
      <path d={`M${x - 3} 37 q3 1.5 6 0`} stroke="#7a4a33" strokeWidth="1.2" fill="none" />
      {children}
    </>
  );
}

const Ok = () => (
  <>
    <circle cx="52" cy="52" r="9" fill="#2f9e5f" />
    <path d="M47.5 52 l3 3 5.5-6" stroke="#fff" strokeWidth="2" fill="none" />
  </>
);
const No = () => (
  <>
    <circle cx="52" cy="52" r="9" fill="#d94a3d" />
    <path d="M48.5 48.5 l7 7 M55.5 48.5 l-7 7" stroke="#fff" strokeWidth="2" />
  </>
);

export type TipKind = 'light' | 'accessories' | 'neutral' | 'eyelevel' | 'one';

export function TipIcon({ kind }: { kind: TipKind }) {
  return (
    <svg viewBox="0 0 64 64" aria-hidden="true">
      {kind === 'light' && (
        <>
          <circle cx="12" cy="12" r="6" fill="#f3b25a" />
          <path
            d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2"
            stroke="#f3b25a"
            strokeWidth="1.6"
          />
          <Face x={36} />
          <Ok />
        </>
      )}
      {kind === 'accessories' && (
        <>
          <Face>
            <rect x="23" y="26" width="8" height="6" rx="2" fill="#222" />
            <rect x="33" y="26" width="8" height="6" rx="2" fill="#222" />
            <path d="M31 29h2" stroke="#222" />
          </Face>
          <No />
        </>
      )}
      {kind === 'neutral' && (
        <>
          <Face />
          <Ok />
        </>
      )}
      {kind === 'eyelevel' && (
        <>
          <Face x={24} />
          <rect x="44" y="20" width="12" height="20" rx="2.5" fill="none" stroke="#6b9bf2" strokeWidth="2" />
          <path d="M36 30 h7" stroke="#6b9bf2" strokeWidth="1.6" strokeDasharray="2 2" />
        </>
      )}
      {kind === 'one' && (
        <>
          <Face x={22} />
          <Face x={44} colour="#b47750" />
          <No />
        </>
      )}
    </svg>
  );
}

export const READY_TIPS: readonly (readonly [TipKind, string])[] = [
  ['light', 'Face the light'],
  ['accessories', 'Remove sunglasses, mask or cap'],
  ['neutral', 'Keep a neutral expression'],
  ['eyelevel', 'Hold the device at eye level'],
  ['one', 'Only one person in view'],
];
