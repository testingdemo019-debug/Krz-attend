/**
 * The prototype's draftPage for the settings pages (F10): edit a copy of the saved values, then save or discard it.
 * The copy is dirty while it differs from the saved values. Leaving the page with unsaved changes asks first (the
 * prototype's "Unsaved changes" dialog), and closing the tab asks the browser's own question.
 */
import { useEffect, useState } from 'react';
import { useBlocker } from 'react-router';
import { STALE_VERSION_MESSAGE, StaleVersionError } from '@/api/client';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Button } from '@/components/ui/Button';

export interface Draft<T> {
  /** The values on the page: the copy being edited, or the saved values while nothing is changed. */
  value: T;
  set: (next: T | ((current: T) => T)) => void;
  dirty: boolean;
  saving: boolean;
  /** Checks, saves and toasts "<title> saved"; false when a check or the server refused. */
  save: () => Promise<boolean>;
  /** Back to the saved values, with the prototype's "Changes discarded" toast. */
  discard: () => void;
  /** Back to the saved values silently (leaving the page without saving). */
  reset: () => void;
}

const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);

export function useDraft<T>({
  title,
  source,
  save,
  validate,
  onStale,
}: {
  /** The page's title, for the toast. */
  title: string;
  /** The saved values. */
  source: T;
  /** Saves the copy; the saved values come back through `source`. */
  save: (draft: T) => Promise<unknown>;
  /** The prototype's validate: a message that stops the save. */
  validate?: (draft: T) => string | null;
  /** Reloads the saved values after someone else saved first. */
  onStale?: () => void;
}): Draft<T> {
  const [edited, setEdited] = useState<T | null>(null);
  const [saving, setSaving] = useState(false);
  const value = edited ?? source;
  const dirty = edited !== null && !same(edited, source);

  const draft: Draft<T> = {
    value,
    dirty,
    saving,
    set: next => {
      setEdited(current => {
        const base = current ?? source;
        return typeof next === 'function' ? (next as (current: T) => T)(base) : next;
      });
    },
    save: async () => {
      const problem = validate?.(value) ?? null;
      if (problem) {
        toast(problem, { type: 'error' });
        return false;
      }
      setSaving(true);
      try {
        await save(value);
        setEdited(null);
        toast(`${title} saved`, { type: 'success', logged: true });
        return true;
      } catch (error) {
        if (error instanceof StaleVersionError) {
          toast(STALE_VERSION_MESSAGE, {
            type: 'error',
            ...(onStale
              ? {
                  action: {
                    label: 'Reload',
                    run: () => {
                      setEdited(null);
                      onStale();
                    },
                  },
                }
              : {}),
          });
        } else {
          toast(error instanceof Error ? error.message : String(error), { type: 'error' });
        }
        return false;
      } finally {
        setSaving(false);
      }
    },
    discard: () => {
      setEdited(null);
      toast('Changes discarded', { timeout: 1600 });
    },
    reset: () => {
      setEdited(null);
    },
  };

  useEffect(() => {
    if (!dirty) return undefined;
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', ask);
    return () => {
      window.removeEventListener('beforeunload', ask);
    };
  }, [dirty]);

  return draft;
}

/** The prototype's "Unsaved changes" dialog, shown when someone leaves a page with a dirty draft. */
export function UnsavedChangesGuard<T>({ draft }: { draft: Draft<T> }) {
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      draft.dirty &&
      (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search),
  );
  if (blocker.state !== 'blocked') return null;
  return (
    <Modal
      open
      title="Unsaved changes"
      size="sm"
      onClose={() => {
        blocker.reset();
      }}
      footer={
        <>
          <Button
            onClick={() => {
              blocker.reset();
            }}
          >
            Stay on page
          </Button>
          <Button
            onClick={() => {
              draft.reset();
              blocker.proceed();
            }}
          >
            Discard changes
          </Button>
          <Button
            variant="primary"
            pending={draft.saving}
            onClick={() => {
              void draft.save().then(ok => {
                if (ok) blocker.proceed();
                else blocker.reset();
              });
            }}
          >
            Save and continue
          </Button>
        </>
      }
    >
      <p>You have unsaved changes on this page. What would you like to do?</p>
    </Modal>
  );
}
