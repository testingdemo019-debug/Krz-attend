import type { InputHTMLAttributes, ReactNode } from 'react';
import { tip } from '@/components/ui/Tooltip';
import './Switch.module.css';

/** The prototype's switchHTML: a native checkbox drawn as a track, with an optional text after it. */
export function Switch({
  label,
  tooltip,
  ...input
}: { label?: ReactNode; tooltip?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  return (
    <label className="switch" {...tip(tooltip)}>
      <input type="checkbox" {...input} />
      <span className="track" />
      {label !== undefined && <span>{label}</span>}
    </label>
  );
}
