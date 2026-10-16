import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './Legend.module.css';

export interface LegendEntry {
  label: ReactNode;
  /** A swatch colour; entries without one are plain text (e.g. "- - average"). */
  colour?: string;
  /** The swatch's outline, e.g. the day timeline's shift band. */
  border?: string;
}

export function Legend({ entries, className }: { entries: readonly LegendEntry[]; className?: string }) {
  return (
    <div className={cx('legend', className)}>
      {entries.map((entry, i) => (
        <span key={i}>
          {entry.colour && (
            <i
              className="sw"
              style={entry.border ? { background: entry.colour, border: entry.border } : { background: entry.colour }}
            />
          )}
          {entry.label}
        </span>
      ))}
    </div>
  );
}
