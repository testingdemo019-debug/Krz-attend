import type { ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import './Chip.module.css';

export type ChipTone = 'green' | 'amber' | 'red' | 'purple' | 'grey' | 'blue';

/** Status chip: tone, optional icon and always a text (colour is never the only signal, F6.11). */
export function Chip({
  tone = 'grey',
  icon,
  title,
  className,
  children,
}: {
  tone?: ChipTone | undefined;
  icon?: IconName | undefined;
  title?: string | undefined;
  className?: string;
  children: ReactNode;
}) {
  return (
    <span className={cx('chip', `chip-${tone}`, className)} title={title}>
      {icon && <Icon name={icon} />}
      {children}
    </span>
  );
}

/** Small square label (".tag"), e.g. "signed in", permission keys, "Local · HRMS later". */
export function Tag({ title, className, children }: { title?: string; className?: string; children: ReactNode }) {
  return (
    <span className={cx('tag', className)} title={title}>
      {children}
    </span>
  );
}

export function RoleBadge({ children }: { children: ReactNode }) {
  return <span className="role-badge">{children}</span>;
}
