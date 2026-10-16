import { cx } from '@/lib/cx';
import './Spinner.module.css';

/** White on dark (camera tiles) by default; `dark` for light surfaces. */
export function Spinner({ size, dark = false, label }: { size?: 'sm'; dark?: boolean; label?: string }) {
  return (
    <span
      className={cx('spinner', size, dark && 'dark')}
      role={label ? 'status' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    />
  );
}
