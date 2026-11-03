import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import { tip, type TipPosition } from './Tooltip';

interface IconButtonLook {
  icon: IconName;
  /** Required: icon-only buttons are named for screen readers (F6.11), and show it as their tooltip (D-121). */
  label: string;
  size?: 'sm' | undefined;
  iconSize?: 'sm' | undefined;
  bordered?: boolean | undefined;
  active?: boolean | undefined;
  tooltip?: string | undefined;
  tooltipPosition?: TipPosition | undefined;
  className?: string | undefined;
  /** A count badge or other overlay (top-right). */
  children?: ReactNode;
}

function classes({ size, bordered, active, className }: IconButtonLook): string {
  return cx('icon-btn', size, bordered && 'bordered', active && 'active', className);
}

export function IconButton({
  icon,
  label,
  size,
  iconSize,
  bordered,
  active,
  tooltip,
  tooltipPosition,
  className,
  children,
  type = 'button',
  ...rest
}: IconButtonLook & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className' | 'children' | 'aria-label'>) {
  return (
    <button
      type={type}
      className={classes({ icon, label, size, bordered, active, className })}
      aria-label={label}
      {...tip(tooltip ?? label, tooltipPosition)}
      {...rest}
    >
      <Icon name={icon} size={iconSize} />
      {children}
    </button>
  );
}

export function IconLink({
  icon,
  label,
  size,
  iconSize,
  bordered,
  active,
  tooltip,
  tooltipPosition,
  className,
  children,
  ...rest
}: IconButtonLook & Omit<LinkProps, 'className' | 'children' | 'aria-label'>) {
  return (
    <Link
      className={classes({ icon, label, size, bordered, active, className })}
      aria-label={label}
      {...tip(tooltip ?? label, tooltipPosition)}
      {...rest}
    >
      <Icon name={icon} size={iconSize} />
      {children}
    </Link>
  );
}
