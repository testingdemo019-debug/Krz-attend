/**
 * The prototype's openPopover on Radix Popover: opens below the trigger, aligned to its left or right edge, flips
 * above when there is no room and stays 8 px inside the window; Escape and an outside click close it.
 */
import * as RadixPopover from '@radix-ui/react-popover';
import type { ButtonHTMLAttributes, CSSProperties, ReactElement, ReactNode } from 'react';
import { keepOpenForCalendar } from '@/components/form/DateField';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import './Popover.module.css';

export function Popover({
  trigger,
  align = 'left',
  width,
  open,
  onOpenChange,
  label,
  style,
  children,
}: {
  /** One focusable element (it gets aria-expanded). */
  trigger: ReactElement;
  align?: 'left' | 'right';
  width?: number;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  label?: string;
  style?: CSSProperties;
  children: ReactNode;
}) {
  return (
    <RadixPopover.Root
      {...(open === undefined ? {} : { open })}
      {...(onOpenChange === undefined ? {} : { onOpenChange })}
    >
      <RadixPopover.Trigger asChild>{trigger}</RadixPopover.Trigger>
      <RadixPopover.Portal>
        <RadixPopover.Content
          className="popover"
          side="bottom"
          align={align === 'right' ? 'end' : 'start'}
          sideOffset={6}
          collisionPadding={8}
          onEscapeKeyDown={keepOpenForCalendar}
          aria-label={label}
          style={width ? { ...style, width } : style}
        >
          {children}
        </RadixPopover.Content>
      </RadixPopover.Portal>
    </RadixPopover.Root>
  );
}

/** Closes the surrounding popover when clicked (wrap a menu item). */
export const PopoverClose = RadixPopover.Close;

export function MenuItem({
  icon,
  danger = false,
  className,
  children,
  type = 'button',
  ...rest
}: { icon?: IconName; danger?: boolean; children: ReactNode } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type={type} className={cx('menu-item', danger && 'danger', className)} {...rest}>
      {icon && <Icon name={icon} />}
      {children}
    </button>
  );
}

export function MenuSeparator() {
  return <div className="menu-sep" role="separator" />;
}

export function MenuLabel({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <div className="menu-label" style={style}>
      {children}
    </div>
  );
}
