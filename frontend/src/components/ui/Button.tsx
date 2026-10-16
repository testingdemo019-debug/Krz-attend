import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import { Spinner } from './Spinner';
import { tip, type TipPosition } from './Tooltip';
import './Button.module.css';

export type ButtonVariant = 'default' | 'primary' | 'danger' | 'ghost';
export type ButtonSize = 'sm' | 'lg';

interface CommonProps {
  variant?: ButtonVariant | undefined;
  size?: ButtonSize | undefined;
  /** Leading icon; small buttons get the small icon unless iconSize says otherwise. */
  icon?: IconName | undefined;
  iconSize?: 'sm' | 'default' | undefined;
  tooltip?: string | undefined;
  tooltipPosition?: TipPosition | undefined;
  className?: string | undefined;
  children?: ReactNode;
}

function classes({ variant = 'default', size, className }: CommonProps): string {
  return cx('btn', variant !== 'default' && `btn-${variant}`, size && `btn-${size}`, className);
}

function leading(icon: IconName | undefined, size: ButtonSize | undefined, iconSize: 'sm' | 'default' | undefined) {
  if (!icon) return null;
  const small = iconSize ? iconSize === 'sm' : size === 'sm';
  return <Icon name={icon} size={small ? 'sm' : undefined} />;
}

export interface ButtonProps
  extends CommonProps, Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className' | 'children'> {
  /** A mutation is running: spinner instead of the icon, and no second submit (F10). */
  pending?: boolean;
}

export function Button({
  variant,
  size,
  icon,
  iconSize,
  tooltip,
  tooltipPosition,
  className,
  children,
  pending = false,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={classes({ variant, size, className })}
      disabled={disabled === true || pending}
      aria-busy={pending || undefined}
      {...tip(tooltip, tooltipPosition)}
      {...rest}
    >
      {pending ? (
        <Spinner size="sm" dark={variant !== 'primary' && variant !== 'danger'} />
      ) : (
        leading(icon, size, iconSize)
      )}
      {children}
    </button>
  );
}

/** A link that looks like a button (the prototype's <a class="btn">). */
export function ButtonLink({
  variant,
  size,
  icon,
  iconSize,
  tooltip,
  tooltipPosition,
  className,
  children,
  ...rest
}: CommonProps & Omit<LinkProps, 'className' | 'children'>) {
  return (
    <Link className={classes({ variant, size, className })} {...tip(tooltip, tooltipPosition)} {...rest}>
      {leading(icon, size, iconSize)}
      {children}
    </Link>
  );
}

/** A download from a server export endpoint, styled as a button (F10: exports are never built client-side). */
export function DownloadButton({
  variant,
  size,
  icon,
  iconSize,
  className,
  children,
  ...rest
}: CommonProps & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'className' | 'children'>) {
  return (
    <a className={classes({ variant, size, className })} download {...rest}>
      {leading(icon, size, iconSize)}
      {children}
    </a>
  );
}
