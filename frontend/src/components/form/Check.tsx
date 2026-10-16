import type { InputHTMLAttributes, ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './Check.module.css';

/** The prototype's label.check: a native checkbox (accent colour) and its text. */
export function Check({
  label,
  className,
  ...input
}: { label: ReactNode; className?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'className'>) {
  return (
    <label className={cx('check', className)}>
      <input type="checkbox" {...input} />
      {label}
    </label>
  );
}
