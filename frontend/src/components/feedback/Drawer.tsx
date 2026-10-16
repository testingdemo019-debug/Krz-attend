/** The prototype's openDrawer on Radix Dialog: a right-hand panel (wide = 760 px) with head, body and foot. */
import * as Dialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import { keepOpenForCalendar } from '@/components/form/DateField';
import { Icon } from '@/components/icons/Icon';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { cx } from '@/lib/cx';
import { focusFirstControl, keepOpenForToasts } from './Modal';
import './Modal.module.css';
import './Drawer.module.css';

export function Drawer({
  open,
  onClose,
  title,
  sub,
  wide = false,
  footer,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  sub?: ReactNode;
  wide?: boolean;
  footer?: ReactNode;
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
          asChild
          aria-describedby={undefined}
          onOpenAutoFocus={focusFirstControl}
          onEscapeKeyDown={keepOpenForCalendar}
          onInteractOutside={keepOpenForToasts}
        >
          <aside className={cx('drawer', wide && 'wide')}>
            <div className="drawer-head">
              <div className="grow">
                <Dialog.Title>{title}</Dialog.Title>
                {sub !== undefined && <div className="muted small mt-4">{sub}</div>}
              </div>
              <Dialog.Close className="icon-btn" aria-label="Close panel">
                <Icon name="x" />
              </Dialog.Close>
            </div>
            <ScrollArea className="drawer-body" label={title}>
              {children}
            </ScrollArea>
            {footer !== undefined && <div className="drawer-foot">{footer}</div>}
          </aside>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
