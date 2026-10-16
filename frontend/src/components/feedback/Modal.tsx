/**
 * The prototype's openModal on Radix Dialog (focus trap, Escape, focus restore). Like the prototype, the first
 * control after the close button gets focus (or the element marked data-autofocus); a non-dismissable modal (the idle
 * warning) ignores Escape and the backdrop and has no close button.
 */
import * as Dialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import { keepOpenForCalendar } from '@/components/form/DateField';
import { Icon } from '@/components/icons/Icon';
import { SCROLL_REGION, ScrollArea } from '@/components/layout/ScrollArea';
import { cx } from '@/lib/cx';
import './Modal.module.css';

export type ModalSize = 'sm' | 'lg' | 'xl';

/** The prototype's autofocus rule for modals and drawers. */
export function focusFirstControl(event: Event): void {
  const container = event.currentTarget as HTMLElement | null;
  if (!container) return;
  event.preventDefault();
  const marked = container.querySelector<HTMLElement>('[data-autofocus]');
  const controls = Array.from(
    container.querySelectorAll<HTMLElement>(
      'button:not([disabled]),[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])',
    ),
  ).filter(el => el.offsetParent !== null && !el.hasAttribute(SCROLL_REGION));
  (marked ?? controls[1] ?? controls[0] ?? container).focus();
}

/**
 * Toasts sit above the backdrop, as in the prototype: using one (Undo after a correction in a drawer) leaves the
 * modal or drawer open.
 */
export function keepOpenForToasts(event: { target: EventTarget | null; preventDefault: () => void }): void {
  if ((event.target as Element | null)?.closest('.toast-stack')) event.preventDefault();
}

export function Modal({
  open,
  onClose,
  title,
  sub,
  size,
  footer,
  dismissable = true,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  sub?: ReactNode;
  size?: ModalSize | undefined;
  footer?: ReactNode;
  dismissable?: boolean;
  children?: ReactNode;
}) {
  return (
    <Dialog.Root
      open={open}
      onOpenChange={next => {
        if (!next) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="backdrop" />
        <Dialog.Content
          className={cx('modal', size)}
          aria-describedby={undefined}
          onOpenAutoFocus={focusFirstControl}
          onEscapeKeyDown={event => {
            if (!dismissable) event.preventDefault();
            keepOpenForCalendar(event);
          }}
          onPointerDownOutside={event => {
            if (!dismissable) event.preventDefault();
          }}
          onInteractOutside={keepOpenForToasts}
        >
          <div className="modal-head">
            <div>
              <Dialog.Title>{title}</Dialog.Title>
              {sub !== undefined && <div className="muted small mt-4">{sub}</div>}
            </div>
            {dismissable && (
              <Dialog.Close className="icon-btn sm" aria-label="Close dialog">
                <Icon name="x" />
              </Dialog.Close>
            )}
          </div>
          <ScrollArea className="modal-body" label={title}>
            {children}
          </ScrollArea>
          {footer !== undefined && <div className="modal-foot">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
