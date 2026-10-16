import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import type { Draft } from '@/lib/useDraft';
import './UnsavedChangesBar.module.css';

/**
 * The prototype's .savebar: "Unsaved changes", Discard and Save changes, stuck to the bottom of the page's card. While
 * it is shown, toasts sit above it (--savebar-h, F6.10).
 */
/** @param start a page action at the bar's left end (Notifications: "Send test e-mail") */
export function UnsavedChangesBar<T>({ draft, start }: { draft: Draft<T>; start?: ReactNode }) {
  const bar = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.style.setProperty('--savebar-h', `${String(bar.current?.offsetHeight ?? 0)}px`);
    return () => {
      root.style.removeProperty('--savebar-h');
    };
  }, []);
  return (
    <div className="savebar" ref={bar}>
      {start}
      <span className="dirty" style={{ visibility: draft.dirty ? 'visible' : 'hidden' }}>
        <Icon name="alertCircle" size="sm" />
        Unsaved changes
      </span>
      <Button disabled={!draft.dirty || draft.saving} onClick={draft.discard}>
        Discard
      </Button>
      <Button
        variant="primary"
        disabled={!draft.dirty}
        pending={draft.saving}
        onClick={() => {
          void draft.save();
        }}
      >
        Save changes
      </Button>
    </div>
  );
}
