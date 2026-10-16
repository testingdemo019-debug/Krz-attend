import { cx } from '@/lib/cx';
import { ICON_PATHS, type IconName } from './paths';

export type { IconName };
export type IconSize = 'sm' | 'lg' | 'xl';

/** The prototype's icon(name, cls): an inline 24×24 stroked SVG with the .ic class (18 px; sm 15, lg 22, xl 32). */
export function Icon({ name, size, className }: { name: IconName; size?: IconSize | undefined; className?: string }) {
  return (
    <svg className={cx('ic', size, className)} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {ICON_PATHS[name]}
    </svg>
  );
}
