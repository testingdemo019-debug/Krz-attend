import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './StatStrip.module.css';

/** The prototype's .stat-strip of small figures (the day drawer's check-in, check-out and worked time). */
export function StatStrip({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cx('stat-strip', className)}>{children}</div>;
}

export function Stat({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <div className="stat">
      <div className="l">{label}</div>
      <div className="v">{value}</div>
      {note}
    </div>
  );
}
