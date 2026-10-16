import type { ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import './Callout.module.css';

export type CalloutTone = 'info' | 'warn' | 'danger' | 'ok' | 'neutral';

const DEFAULT_ICON: Record<CalloutTone, IconName> = {
  info: 'info',
  warn: 'alert',
  danger: 'alertCircle',
  ok: 'checkCircle',
  neutral: 'info',
};

/** A tinted note with an icon (info is the blue default). */
export function Callout({
  tone = 'info',
  icon,
  role,
  className,
  children,
}: {
  tone?: CalloutTone;
  /** null: no icon (the prototype's plain neutral boxes). */
  icon?: IconName | null;
  role?: 'status' | 'alert';
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cx('callout', tone !== 'info' && tone, className)} role={role}>
      {icon !== null && <Icon name={icon ?? DEFAULT_ICON[tone]} />}
      <div>{children}</div>
    </div>
  );
}
