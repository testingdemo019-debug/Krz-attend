import { cx } from '@/lib/cx';
import './Badge.module.css';

/** Count badge: red (urgent) by default, neutral or blue. */
export function Badge({
  count,
  tone,
  label,
  className,
}: {
  count: number | string;
  tone?: 'neutral' | 'blue';
  label?: string;
  className?: string;
}) {
  return (
    <span className={cx('badge', tone, className)} aria-label={label}>
      {count}
    </span>
  );
}
