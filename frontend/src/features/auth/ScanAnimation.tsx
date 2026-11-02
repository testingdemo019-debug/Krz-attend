/**
 * The sign-in screen's face-scan loop (D-121, D-122), decorative: a face of glowing dots turns in the camera's view
 * while a scan line passes and its landmarks light up, the dots gather into a check, the person is named as verified
 * and joins today's list. The dots are drawn by faceScan.ts on a canvas; the captions follow the loop through the
 * stage's `data-phase`. With reduced motion it rests on the identified frame; it only runs while on screen.
 */
import { useEffect, useRef } from 'react';
import { LOOP_MS, STILL_MS, phaseAt, scanPainter, type ScanPhase } from './faceScan';

/** Sample people: the one being recognised, then two already in today. */
const PERSON = { name: 'Ameer Khan', at: '09:03' };
const FEED: readonly (readonly [string, string])[] = [
  ['Santhosh Kumar', '09:02'],
  ['Ajith Kumar', '09:01'],
];

function FeedRow({ name, at, className = 'scan-row' }: { name: string; at: string; className?: string }) {
  return (
    <div className={className}>
      <span className="scan-av">{name.charAt(0)}</span>
      <span className="grow">{name}</span>
      <span className="scan-in">IN</span>
      <span className="scan-at">{at}</span>
    </div>
  );
}

export function ScanAnimation() {
  const stage = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const root = stage.current;
    const el = canvas.current;
    const paint = el && scanPainter(el);
    if (!root || !el || !paint) return;
    const still = window.matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    let visible = true;
    const show = (phase: ScanPhase) => {
      if (root.dataset.phase !== phase) root.dataset.phase = phase;
    };
    const tick = (now: number) => {
      const t = now % LOOP_MS;
      paint(t);
      show(phaseAt(t));
      frame = requestAnimationFrame(tick);
    };
    const run = () => {
      cancelAnimationFrame(frame);
      if (still.matches) {
        paint(STILL_MS);
        show('verified');
      } else if (visible) {
        frame = requestAnimationFrame(tick);
      }
    };
    const seen = new IntersectionObserver(([entry]) => {
      visible = entry?.isIntersecting ?? true;
      run();
    });
    const resized = new ResizeObserver(() => {
      if (still.matches) paint(STILL_MS);
    });
    seen.observe(el);
    resized.observe(el);
    still.addEventListener('change', run);
    run();
    return () => {
      cancelAnimationFrame(frame);
      seen.disconnect();
      resized.disconnect();
      still.removeEventListener('change', run);
    };
  }, []);

  return (
    <div ref={stage} className="scan-stage" data-phase="scan" aria-hidden="true">
      <div className="scan-cam">
        <canvas ref={canvas} className="scan-canvas" />
        <div className="scan-say">Recognising face</div>
        <div className="scan-chip">
          <svg viewBox="0 0 16 16" className="scan-ok">
            <circle cx="8" cy="8" r="8" />
            <path
              d="M4.6 8.2l2.3 2.3 4.6-4.9"
              fill="none"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <b>{PERSON.name}</b> · Verified · IN {PERSON.at}
        </div>
      </div>
      <div className="scan-feed">
        <div className="scan-feed-head">Today</div>
        <FeedRow name={PERSON.name} at={PERSON.at} className="scan-row scan-row-new" />
        {FEED.map(([name, at]) => (
          <FeedRow key={name} name={name} at={at} />
        ))}
      </div>
    </div>
  );
}
